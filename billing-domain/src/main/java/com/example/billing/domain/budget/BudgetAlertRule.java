package com.example.billing.domain.budget;

import com.example.billing.domain.shared.CustomerId;
import com.example.billing.domain.shared.Money;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Currency;
import java.util.Objects;
import java.util.Optional;

/**
 * 예산 알림 규칙 — "월말 예상 청구액이 X 원 이상이면 알림" 같은 것.
 *
 * <p>스케줄러가 주기적으로 {@link #evaluate} 호출. 임계 초과 + cooldown 지나면 Triggered 이벤트 반환.
 * cooldown 으로 같은 사용자에게 동일 알림이 매 분 가는 것을 방지 (기본 24h).</p>
 *
 * <p><b>도메인 invariant</b>:
 * <ul>
 *   <li>{@code threshold > 0}</li>
 *   <li>모든 evaluate 의 projectedCost 는 {@code threshold.currency} 와 같아야 함</li>
 * </ul>
 *
 * <p>한 customer 가 여러 rule 보유 가능 (e.g., $100 yellow / $500 red 처럼 단계적 알림).
 * 도메인은 그 정책을 모름 — application service 가 list 를 돌며 evaluate 호출.</p>
 */
public final class BudgetAlertRule {

    /** 같은 rule 이 다시 트리거되기까지 최소 간격. */
    private static final Duration DEFAULT_COOLDOWN = Duration.ofHours(24);

    private final BudgetAlertRuleId id;
    private final CustomerId customerId;
    private final Money threshold;
    private final Duration cooldown;
    private BudgetAlertStatus status;
    private Instant lastEvaluatedAt;
    private Instant lastTriggeredAt;
    private final Instant createdAt;
    private Instant updatedAt;
    private long version;

    private BudgetAlertRule(BudgetAlertRuleId id, CustomerId customerId, Money threshold,
                            Duration cooldown, BudgetAlertStatus status,
                            Instant lastEvaluatedAt, Instant lastTriggeredAt,
                            Instant createdAt, Instant updatedAt, long version) {
        this.id = id;
        this.customerId = customerId;
        this.threshold = threshold;
        this.cooldown = cooldown;
        this.status = status;
        this.lastEvaluatedAt = lastEvaluatedAt;
        this.lastTriggeredAt = lastTriggeredAt;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.version = version;
    }

    public static BudgetAlertRule create(CustomerId customerId, Money threshold, Clock clock) {
        return create(customerId, threshold, DEFAULT_COOLDOWN, clock);
    }

    public static BudgetAlertRule create(CustomerId customerId, Money threshold,
                                         Duration cooldown, Clock clock) {
        Objects.requireNonNull(customerId);
        Objects.requireNonNull(threshold);
        Objects.requireNonNull(cooldown);
        if (!threshold.isPositive()) {
            throw new IllegalArgumentException("threshold must be positive: " + threshold);
        }
        if (cooldown.isNegative() || cooldown.isZero()) {
            throw new IllegalArgumentException("cooldown must be positive: " + cooldown);
        }
        Instant now = clock.instant();
        return new BudgetAlertRule(BudgetAlertRuleId.newId(), customerId, threshold, cooldown,
                BudgetAlertStatus.ACTIVE, null, null, now, now, 0L);
    }

    public static BudgetAlertRule restore(BudgetAlertRuleId id, CustomerId customerId,
                                          Money threshold, Duration cooldown,
                                          BudgetAlertStatus status,
                                          Instant lastEvaluatedAt, Instant lastTriggeredAt,
                                          Instant createdAt, Instant updatedAt, long version) {
        return new BudgetAlertRule(id, customerId, threshold, cooldown, status,
                lastEvaluatedAt, lastTriggeredAt, createdAt, updatedAt, version);
    }

    /**
     * 평가. 임계 초과 + cooldown 지났으면 Triggered 이벤트 반환, 아니면 empty.
     * 둘 다 lastEvaluatedAt 은 갱신.
     *
     * @param projectedCost 동일 통화여야 함 (UsageForecast 결과)
     */
    public Optional<BudgetAlertEvents.Triggered> evaluate(Money projectedCost, Clock clock) {
        Objects.requireNonNull(projectedCost);
        if (!projectedCost.currency().equals(threshold.currency())) {
            throw new IllegalArgumentException(
                    "currency mismatch: rule=" + threshold.currency()
                            + " projected=" + projectedCost.currency());
        }
        Instant now = clock.instant();
        this.lastEvaluatedAt = now;
        this.updatedAt = now;

        if (status != BudgetAlertStatus.ACTIVE) return Optional.empty();
        if (projectedCost.compareTo(threshold) < 0) return Optional.empty();
        if (lastTriggeredAt != null && Duration.between(lastTriggeredAt, now).compareTo(cooldown) < 0) {
            return Optional.empty();   // cooldown 안 지남
        }

        this.lastTriggeredAt = now;
        double ratio = projectedCost.amount().doubleValue() / threshold.amount().doubleValue();
        return Optional.of(new BudgetAlertEvents.Triggered(
                id, customerId, threshold, projectedCost, ratio, now));
    }

    public BudgetAlertEvents.Paused pause(Clock clock) {
        if (status != BudgetAlertStatus.ACTIVE) {
            throw new IllegalStateException("only ACTIVE can be paused: status=" + status);
        }
        Instant now = clock.instant();
        this.status = BudgetAlertStatus.PAUSED;
        this.updatedAt = now;
        return new BudgetAlertEvents.Paused(id, customerId, now);
    }

    public BudgetAlertEvents.Resumed resume(Clock clock) {
        if (status != BudgetAlertStatus.PAUSED) {
            throw new IllegalStateException("only PAUSED can be resumed: status=" + status);
        }
        Instant now = clock.instant();
        this.status = BudgetAlertStatus.ACTIVE;
        this.updatedAt = now;
        return new BudgetAlertEvents.Resumed(id, customerId, now);
    }

    public Currency currency() { return threshold.currency(); }

    // Getters
    public BudgetAlertRuleId id() { return id; }
    public CustomerId customerId() { return customerId; }
    public Money threshold() { return threshold; }
    public Duration cooldown() { return cooldown; }
    public BudgetAlertStatus status() { return status; }
    public Instant lastEvaluatedAt() { return lastEvaluatedAt; }
    public Instant lastTriggeredAt() { return lastTriggeredAt; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
    public long version() { return version; }
}
