package com.example.billing.domain.budget;

import com.example.billing.domain.shared.CustomerId;
import com.example.billing.domain.shared.Money;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Currency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BudgetAlertRuleTest {

    private static final Currency KRW = Currency.getInstance("KRW");
    private static final Currency USD = Currency.getInstance("USD");
    private static final CustomerId ALICE = CustomerId.of("alice");
    private static final Instant NOW = Instant.parse("2026-05-15T10:00:00Z");

    private static Money won(long n) {
        return Money.of(BigDecimal.valueOf(n), KRW);
    }

    private static Clock fixedAt(Instant t) {
        return Clock.fixed(t, ZoneOffset.UTC);
    }

    @Test
    void create_thresholdMustBePositive() {
        assertThatThrownBy(() ->
                BudgetAlertRule.create(ALICE, won(0), fixedAt(NOW)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");
    }

    @Test
    void create_cooldownMustBePositive() {
        assertThatThrownBy(() ->
                BudgetAlertRule.create(ALICE, won(100_000), Duration.ZERO, fixedAt(NOW)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cooldown");
    }

    @Test
    void evaluate_belowThreshold_returnsEmptyButUpdatesLastEvaluated() {
        BudgetAlertRule r = BudgetAlertRule.create(ALICE, won(100_000), fixedAt(NOW));
        var triggered = r.evaluate(won(50_000), fixedAt(NOW.plusSeconds(60)));
        assertThat(triggered).isEmpty();
        assertThat(r.lastEvaluatedAt()).isEqualTo(NOW.plusSeconds(60));
        assertThat(r.lastTriggeredAt()).isNull();
    }

    @Test
    void evaluate_atThreshold_triggers() {
        BudgetAlertRule r = BudgetAlertRule.create(ALICE, won(100_000), fixedAt(NOW));
        var triggered = r.evaluate(won(100_000), fixedAt(NOW.plusSeconds(60)));
        assertThat(triggered).isPresent();
        assertThat(triggered.get().overshootRatio()).isEqualTo(1.0);
        assertThat(r.lastTriggeredAt()).isEqualTo(NOW.plusSeconds(60));
    }

    @Test
    void evaluate_overThreshold_triggersWithRatio() {
        BudgetAlertRule r = BudgetAlertRule.create(ALICE, won(100_000), fixedAt(NOW));
        var triggered = r.evaluate(won(150_000), fixedAt(NOW.plusSeconds(60)));
        assertThat(triggered).isPresent();
        assertThat(triggered.get().overshootRatio()).isEqualTo(1.5);
        assertThat(triggered.get().projectedCost()).isEqualTo(won(150_000));
    }

    @Test
    void evaluate_withinCooldown_doesNotRetrigger() {
        Duration cooldown = Duration.ofHours(24);
        BudgetAlertRule r = BudgetAlertRule.create(ALICE, won(100_000), cooldown, fixedAt(NOW));
        // 첫 트리거
        r.evaluate(won(150_000), fixedAt(NOW));
        // 1시간 뒤 — cooldown 안
        var second = r.evaluate(won(200_000), fixedAt(NOW.plusSeconds(3600)));
        assertThat(second).isEmpty();
    }

    @Test
    void evaluate_afterCooldown_canRetrigger() {
        Duration cooldown = Duration.ofHours(24);
        BudgetAlertRule r = BudgetAlertRule.create(ALICE, won(100_000), cooldown, fixedAt(NOW));
        r.evaluate(won(150_000), fixedAt(NOW));
        Instant later = NOW.plus(Duration.ofHours(25));
        var second = r.evaluate(won(200_000), fixedAt(later));
        assertThat(second).isPresent();
        assertThat(r.lastTriggeredAt()).isEqualTo(later);
    }

    @Test
    void evaluate_paused_neverTriggers() {
        BudgetAlertRule r = BudgetAlertRule.create(ALICE, won(100_000), fixedAt(NOW));
        r.pause(fixedAt(NOW.plusSeconds(10)));
        var triggered = r.evaluate(won(500_000), fixedAt(NOW.plusSeconds(20)));
        assertThat(triggered).isEmpty();
        // 평가 자체는 lastEvaluatedAt 갱신
        assertThat(r.lastEvaluatedAt()).isEqualTo(NOW.plusSeconds(20));
    }

    @Test
    void evaluate_currencyMismatch_throws() {
        BudgetAlertRule r = BudgetAlertRule.create(ALICE, won(100_000), fixedAt(NOW));
        Money usd = Money.of(BigDecimal.valueOf(100), USD);
        assertThatThrownBy(() -> r.evaluate(usd, fixedAt(NOW)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("currency mismatch");
    }

    @Test
    void pauseResume_lifecycleTransitions() {
        BudgetAlertRule r = BudgetAlertRule.create(ALICE, won(100_000), fixedAt(NOW));
        r.pause(fixedAt(NOW.plusSeconds(10)));
        assertThat(r.status()).isEqualTo(BudgetAlertStatus.PAUSED);

        r.resume(fixedAt(NOW.plusSeconds(20)));
        assertThat(r.status()).isEqualTo(BudgetAlertStatus.ACTIVE);

        // 두 번 pause 안 됨
        r.pause(fixedAt(NOW.plusSeconds(30)));
        assertThatThrownBy(() -> r.pause(fixedAt(NOW.plusSeconds(40))))
                .isInstanceOf(IllegalStateException.class);
    }
}
