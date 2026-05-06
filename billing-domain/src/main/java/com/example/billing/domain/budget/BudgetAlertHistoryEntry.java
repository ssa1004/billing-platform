package com.example.billing.domain.budget;

import com.example.billing.domain.settlement.BillingPeriod;
import com.example.billing.domain.shared.CustomerId;
import com.example.billing.domain.shared.Money;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * BudgetAlertRule 이 실제로 트리거된 사실의 영속 기록 (append-only).
 *
 * <p>{@link BudgetAlertRule} 자체는 가장 최근 트리거 시각만 들고 있다 (cooldown 계산 용).
 * 이력 ("최근 30일 내 임계 초과 횟수", "처음 임계 초과 시점") 같은 분석은 별도 테이블이 자연스러움.</p>
 *
 * <p>아래 필드는 알림 시점의 *snapshot* — rule 이 나중에 변경돼도 (예: threshold 상향) 과거
 * 트리거의 의미가 변하지 않도록 모두 저장한다.</p>
 */
public record BudgetAlertHistoryEntry(
        UUID id,
        BudgetAlertRuleId ruleId,
        CustomerId customerId,
        Money thresholdAtTrigger,
        Money projectedCostAtTrigger,
        double overshootRatio,
        BillingPeriod period,
        double periodProgressRatioAtTrigger,
        Instant occurredAt
) {

    public BudgetAlertHistoryEntry {
        Objects.requireNonNull(id);
        Objects.requireNonNull(ruleId);
        Objects.requireNonNull(customerId);
        Objects.requireNonNull(thresholdAtTrigger);
        Objects.requireNonNull(projectedCostAtTrigger);
        Objects.requireNonNull(period);
        Objects.requireNonNull(occurredAt);
        if (!thresholdAtTrigger.currency().equals(projectedCostAtTrigger.currency())) {
            throw new IllegalArgumentException(
                    "currency mismatch: threshold=" + thresholdAtTrigger.currency()
                            + " projected=" + projectedCostAtTrigger.currency());
        }
        if (overshootRatio < 1.0) {
            throw new IllegalArgumentException(
                    "overshootRatio must be >= 1.0 (triggered means projected >= threshold): " + overshootRatio);
        }
        if (periodProgressRatioAtTrigger < 0.0 || periodProgressRatioAtTrigger > 1.0) {
            throw new IllegalArgumentException(
                    "periodProgress out of [0,1]: " + periodProgressRatioAtTrigger);
        }
    }

    public static BudgetAlertHistoryEntry from(BudgetAlertEvents.Triggered ev,
                                               BillingPeriod period,
                                               double periodProgressRatio) {
        return new BudgetAlertHistoryEntry(
                UUID.randomUUID(),
                ev.ruleId(),
                ev.customerId(),
                ev.threshold(),
                ev.projectedCost(),
                ev.overshootRatio(),
                period,
                periodProgressRatio,
                ev.occurredAt()
        );
    }
}
