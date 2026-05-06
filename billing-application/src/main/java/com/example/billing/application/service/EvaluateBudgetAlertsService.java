package com.example.billing.application.service;

import com.example.billing.application.exception.PricingPlanNotFoundException;
import com.example.billing.application.port.in.EvaluateBudgetAlertsUseCase;
import com.example.billing.application.port.in.UsageForecastUseCase;
import com.example.billing.application.port.out.BudgetAlertRuleRepository;
import com.example.billing.application.port.out.CustomerNotifier;
import com.example.billing.application.port.out.EventPublisher;
import com.example.billing.domain.budget.BudgetAlertEvents;
import com.example.billing.domain.budget.BudgetAlertRule;
import com.example.billing.domain.metering.UsageForecast;
import com.example.billing.domain.shared.CustomerId;
import com.example.billing.domain.shared.Money;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * BudgetAlertRule 일괄 평가. 스케줄러가 1시간마다 호출.
 *
 * <p>흐름:
 * <ol>
 *   <li>ACTIVE rule 이 있는 customer 목록 조회</li>
 *   <li>각 customer 에 대해: UsageForecast 계산 (현재 BillingPeriod)</li>
 *   <li>해당 customer 의 ACTIVE rule 들을 forecast.projectedTotalCost 와 비교</li>
 *   <li>임계 초과 + cooldown 지난 rule → Triggered 이벤트 발행 + CustomerNotifier 발송</li>
 *   <li>모든 evaluate 호출은 lastEvaluatedAt 갱신 → save</li>
 * </ol>
 *
 * <p>customer 단위 트랜잭션. 한 customer 의 forecast 가 PricingPlanNotFoundException 으로
 * 실패해도 다른 customer 평가는 계속 진행 (catch + log).</p>
 *
 * <p>다중 통화: forecast.projectedTotalCost 의 통화와 rule.threshold 통화가 다르면 skip
 * (현재는 두 곳 모두 단일 통화 가정 — 멀티 currency 분기는 후속).</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EvaluateBudgetAlertsService implements EvaluateBudgetAlertsUseCase {

    private final BudgetAlertRuleRepository rules;
    private final UsageForecastUseCase forecast;
    private final CustomerNotifier notifier;
    private final EventPublisher events;
    private final Clock clock;

    @Override
    public int evaluateAll() {
        List<CustomerId> customers = rules.findCustomersWithActiveRules();
        int evaluated = 0;
        for (CustomerId customerId : customers) {
            try {
                evaluateForCustomer(customerId);
                evaluated++;
            } catch (PricingPlanNotFoundException ex) {
                log.warn("skip budget evaluation: no plan customer={}", customerId);
            } catch (RuntimeException ex) {
                log.error("budget evaluation failed customer={}", customerId, ex);
            }
        }
        log.info("budget alerts evaluated customers={}/{}", evaluated, customers.size());
        return evaluated;
    }

    @Transactional
    protected void evaluateForCustomer(CustomerId customerId) {
        UsageForecast f = forecast.forecastCurrentPeriod(customerId);
        Money projected = f.projectedTotalCost();

        for (BudgetAlertRule rule : rules.findActiveByCustomer(customerId)) {
            if (!rule.threshold().currency().equals(projected.currency())) continue;

            rule.evaluate(projected, clock).ifPresent(triggered -> {
                events.publish(triggered);
                notifier.notify(customerId, CustomerNotifier.NotificationType.BUDGET_ALERT,
                        notificationContext(triggered, f));
                log.info("budget alert TRIGGERED rule={} customer={} threshold={} projected={} ratio={}",
                        rule.id(), customerId, rule.threshold(), projected, triggered.overshootRatio());
            });
            rules.save(rule);   // lastEvaluatedAt / lastTriggeredAt 갱신 보장
        }
    }

    private static Map<String, Object> notificationContext(BudgetAlertEvents.Triggered ev,
                                                           UsageForecast f) {
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("ruleId", ev.ruleId().toString());
        ctx.put("threshold", ev.threshold().amount());
        ctx.put("projectedCost", ev.projectedCost().amount());
        ctx.put("currency", ev.threshold().currency().getCurrencyCode());
        ctx.put("overshootRatio", ev.overshootRatio());
        ctx.put("period", f.period().toKey());
        ctx.put("periodProgressRatio", f.periodProgressRatio());
        return ctx;
    }
}
