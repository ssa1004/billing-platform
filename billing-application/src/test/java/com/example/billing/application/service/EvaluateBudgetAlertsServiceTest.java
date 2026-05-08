package com.example.billing.application.service;

import com.example.billing.application.exception.PricingPlanNotFoundException;
import com.example.billing.application.port.in.UsageForecastUseCase;
import com.example.billing.application.port.out.BudgetAlertHistoryRepository;
import com.example.billing.application.port.out.BudgetAlertRuleRepository;
import com.example.billing.application.port.out.CustomerNotifier;
import com.example.billing.application.port.out.EventPublisher;
import com.example.billing.domain.budget.BudgetAlertEvents;
import com.example.billing.domain.budget.BudgetAlertHistoryEntry;
import com.example.billing.domain.budget.BudgetAlertRule;
import com.example.billing.domain.metering.UsageForecast;
import com.example.billing.domain.settlement.BillingPeriod;
import com.example.billing.domain.shared.CustomerId;
import com.example.billing.domain.shared.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EvaluateBudgetAlertsServiceTest {

    private static final Currency KRW = Currency.getInstance("KRW");
    private static final Currency USD = Currency.getInstance("USD");
    private static final Instant NOW = Instant.parse("2026-05-15T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final CustomerId ALICE = CustomerId.of("alice");
    private static final CustomerId BOB = CustomerId.of("bob");
    private static final BillingPeriod MAY_2026 = BillingPeriod.of(YearMonth.of(2026, 5));

    @Mock BudgetAlertRuleRepository rules;
    @Mock BudgetAlertHistoryRepository history;
    @Mock UsageForecastUseCase forecast;
    @Mock CustomerNotifier notifier;
    @Mock EventPublisher events;

    EvaluateBudgetAlertsService service;

    @BeforeEach
    void setUp() {
        // 테스트에서는 self-invocation 시 진짜 트랜잭션이 필요 없으므로 ObjectProvider 가
        // 자기 자신을 반환하도록 wire — proxy 가 아니라 실제 인스턴스라도 evaluateForCustomer 가
        // 호출되면 충분.
        service = new EvaluateBudgetAlertsService(rules, history, forecast, notifier, events, CLOCK,
                new SelfProvider());
    }

    /** 테스트용 — service 가 자기 자신을 반환하는 ObjectProvider stub. */
    private class SelfProvider implements org.springframework.beans.factory.ObjectProvider<EvaluateBudgetAlertsService> {
        @Override public EvaluateBudgetAlertsService getObject() { return service; }
        @Override public EvaluateBudgetAlertsService getObject(Object... args) { return service; }
        @Override public EvaluateBudgetAlertsService getIfAvailable() { return service; }
        @Override public EvaluateBudgetAlertsService getIfUnique() { return service; }
    }

    private static Money won(long n) {
        return Money.of(BigDecimal.valueOf(n), KRW);
    }

    private static UsageForecast forecastOf(CustomerId customerId, long projected) {
        return new UsageForecast(customerId, MAY_2026, NOW, 0.5, List.of(), won(projected));
    }

    @Test
    void overThreshold_triggersAndSavesHistoryAndNotifies() {
        var rule = BudgetAlertRule.create(ALICE, won(100_000), CLOCK);
        when(rules.findCustomersWithActiveRules()).thenReturn(List.of(ALICE));
        when(rules.findActiveByCustomer(ALICE)).thenReturn(List.of(rule));
        when(forecast.forecastCurrentPeriod(ALICE)).thenReturn(forecastOf(ALICE, 150_000));

        int evaluated = service.evaluateAll();

        assertThat(evaluated).isEqualTo(1);
        ArgumentCaptor<BudgetAlertEvents.Triggered> evCaptor =
                ArgumentCaptor.forClass(BudgetAlertEvents.Triggered.class);
        verify(events).publish(evCaptor.capture());
        assertThat(evCaptor.getValue().overshootRatio()).isEqualTo(1.5);
        verify(history).save(any(BudgetAlertHistoryEntry.class));
        verify(notifier).notify(eqId(ALICE), eq(CustomerNotifier.NotificationType.BUDGET_ALERT), any());
        verify(rules).save(rule);
    }

    @Test
    void belowThreshold_noTriggerNoHistoryNoNotify_butStillSavesRuleForLastEvaluatedAt() {
        var rule = BudgetAlertRule.create(ALICE, won(100_000), CLOCK);
        when(rules.findCustomersWithActiveRules()).thenReturn(List.of(ALICE));
        when(rules.findActiveByCustomer(ALICE)).thenReturn(List.of(rule));
        when(forecast.forecastCurrentPeriod(ALICE)).thenReturn(forecastOf(ALICE, 50_000));

        service.evaluateAll();

        verify(events, never()).publish(any());
        verify(history, never()).save(any());
        verify(notifier, never()).notify(any(), any(), any());
        verify(rules).save(rule);   // lastEvaluatedAt 갱신은 항상
    }

    @Test
    void currencyMismatch_skipsRuleEntirely() {
        // rule = KRW, forecast = USD → skip (evaluate 호출 X, save 도 X)
        var rule = BudgetAlertRule.create(ALICE, won(100_000), CLOCK);
        when(rules.findCustomersWithActiveRules()).thenReturn(List.of(ALICE));
        when(rules.findActiveByCustomer(ALICE)).thenReturn(List.of(rule));
        UsageForecast usdForecast = new UsageForecast(ALICE, MAY_2026, NOW, 0.5, List.of(),
                Money.of(BigDecimal.valueOf(500), USD));
        when(forecast.forecastCurrentPeriod(ALICE)).thenReturn(usdForecast);

        service.evaluateAll();

        verify(events, never()).publish(any());
        verify(history, never()).save(any());
        verify(rules, never()).save(any());
    }

    @Test
    void noPlan_skipsCustomerButContinuesOthers() {
        var aliceRule = BudgetAlertRule.create(ALICE, won(100_000), CLOCK);
        var bobRule = BudgetAlertRule.create(BOB, won(50_000), CLOCK);
        when(rules.findCustomersWithActiveRules()).thenReturn(List.of(ALICE, BOB));
        when(forecast.forecastCurrentPeriod(ALICE))
                .thenThrow(new PricingPlanNotFoundException(ALICE, NOW));
        when(forecast.forecastCurrentPeriod(BOB)).thenReturn(forecastOf(BOB, 75_000));
        when(rules.findActiveByCustomer(BOB)).thenReturn(List.of(bobRule));

        int evaluated = service.evaluateAll();

        // ALICE 는 skip, BOB 만 evaluate
        assertThat(evaluated).isEqualTo(1);
        verify(history, times(1)).save(any());      // BOB triggered
        verify(rules).save(bobRule);
        verify(rules, never()).save(aliceRule);     // ALICE rule 은 손대지 않음
    }

    @Test
    void multipleRulesPerCustomer_eachEvaluated() {
        var yellowRule = BudgetAlertRule.create(ALICE, won(50_000), CLOCK);
        var redRule = BudgetAlertRule.create(ALICE, won(100_000), CLOCK);
        when(rules.findCustomersWithActiveRules()).thenReturn(List.of(ALICE));
        when(rules.findActiveByCustomer(ALICE)).thenReturn(List.of(yellowRule, redRule));
        when(forecast.forecastCurrentPeriod(ALICE)).thenReturn(forecastOf(ALICE, 75_000));

        service.evaluateAll();

        // yellow 만 트리거, red 는 미만
        verify(events, times(1)).publish(any(BudgetAlertEvents.Triggered.class));
        verify(history, times(1)).save(any());
        verify(rules, times(2)).save(any());   // 두 rule 다 lastEvaluatedAt 갱신
    }

    // helpers
    private static <T> T eq(T value) { return org.mockito.ArgumentMatchers.eq(value); }
    private static CustomerId eqId(CustomerId v) { return org.mockito.ArgumentMatchers.eq(v); }
}
