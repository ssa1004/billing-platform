package com.example.billing.application.service

import com.example.billing.application.exception.PricingPlanNotFoundException
import com.example.billing.application.port.`in`.EvaluateBudgetAlertsUseCase
import com.example.billing.application.port.`in`.UsageForecastUseCase
import com.example.billing.application.port.out.BudgetAlertHistoryRepository
import com.example.billing.application.port.out.BudgetAlertRuleRepository
import com.example.billing.application.port.out.CustomerNotifier
import com.example.billing.application.port.out.EventPublisher
import com.example.billing.domain.budget.BudgetAlertEvents
import com.example.billing.domain.budget.BudgetAlertHistoryEntry
import com.example.billing.domain.metering.UsageForecast
import com.example.billing.domain.shared.CustomerId
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import kotlin.jvm.optionals.getOrNull

/**
 * BudgetAlertRule (예산 임계 알림 규칙) 일괄 평가. 스케줄러가 1시간마다 호출.
 *
 * 흐름:
 *  1. ACTIVE rule 이 있는 customer 목록 조회
 *  2. 각 customer 에 대해: UsageForecast (현재 BillingPeriod 의 사용량/비용 예측) 계산
 *  3. 해당 customer 의 ACTIVE rule 들을 forecast.projectedTotalCost 와 비교
 *  4. 임계를 넘었고 cooldown (재트리거 사이의 휴지 간격) 도 지났다면 Triggered 이벤트 발행
 *     + CustomerNotifier 로 발송
 *  5. 모든 evaluate 호출은 lastEvaluatedAt 을 갱신하므로 save
 *
 * customer 단위 트랜잭션입니다. 한 customer 의 forecast 가 PricingPlanNotFoundException
 * 으로 실패해도 다른 customer 평가는 계속 진행합니다 (catch + log).
 *
 * 다중 통화: forecast.projectedTotalCost 의 통화와 rule.threshold 통화가 다르면 skip
 * (현재는 두 곳 모두 단일 통화 가정 — 다중 통화 분기는 후속).
 */
@Service
open class EvaluateBudgetAlertsService(
    private val rules: BudgetAlertRuleRepository,
    private val history: BudgetAlertHistoryRepository,
    private val forecast: UsageForecastUseCase,
    private val notifier: CustomerNotifier,
    private val events: EventPublisher,
    private val clock: Clock,
    /**
     * 같은 빈을 self-injection — proxy 를 거쳐 호출해야 `@Transactional` 이 동작합니다.
     * 직접 `this.evaluateForCustomer(...)` 를 호출하면 Spring AOP proxy 가 끼지 않아
     * 트랜잭션이 시작되지 않습니다 (self-invocation 함정).
     */
    private val selfProvider: ObjectProvider<EvaluateBudgetAlertsService>,
) : EvaluateBudgetAlertsUseCase {

    override fun evaluateAll(): Int {
        val customers = rules.findCustomersWithActiveRules()
        val self = selfProvider.getObject()
        var evaluated = 0
        for (customerId in customers) {
            try {
                self.evaluateForCustomer(customerId)
                evaluated++
            } catch (ex: PricingPlanNotFoundException) {
                log.warn("skip budget evaluation: no plan customer={}", customerId)
            } catch (ex: RuntimeException) {
                log.error("budget evaluation failed customer={}", customerId, ex)
            }
        }
        log.info("budget alerts evaluated customers={}/{}", evaluated, customers.size)
        return evaluated
    }

    /**
     * **주의**: `public` (Kotlin default) 이어야 Spring CGLIB proxy 가 인터셉트해서
     * `@Transactional` 을 적용합니다 (protected / package private 는 안 잡힘). 외부 빈이
     * 직접 호출하는 의도는 없지만 가시성은 노출.
     */
    @Transactional
    open fun evaluateForCustomer(customerId: CustomerId) {
        val f = forecast.forecastCurrentPeriod(customerId)
        val projected = f.projectedTotalCost

        for (rule in rules.findActiveByCustomer(customerId)) {
            if (rule.threshold.currency != projected.currency) continue

            rule.evaluate(projected, clock).getOrNull()?.let { triggered ->
                events.publish(triggered)
                history.save(
                    BudgetAlertHistoryEntry.from(triggered, f.period, f.periodProgressRatio),
                )
                notifier.notify(
                    customerId, CustomerNotifier.NotificationType.BUDGET_ALERT,
                    notificationContext(triggered, f),
                )
                log.info(
                    "budget alert TRIGGERED rule={} customer={} threshold={} projected={} ratio={}",
                    rule.id, customerId, rule.threshold, projected, triggered.overshootRatio,
                )
            }
            // 트리거 여부와 무관하게 lastEvaluatedAt / lastTriggeredAt 가 갱신되도록 항상 save
            rules.save(rule)
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(EvaluateBudgetAlertsService::class.java)

        private fun notificationContext(
            ev: BudgetAlertEvents.Triggered,
            f: UsageForecast,
        ): Map<String, Any> {
            val ctx = HashMap<String, Any>()
            ctx["ruleId"] = ev.ruleId.toString()
            ctx["threshold"] = ev.threshold.amount
            ctx["projectedCost"] = ev.projectedCost.amount
            ctx["currency"] = ev.threshold.currency.currencyCode
            ctx["overshootRatio"] = ev.overshootRatio
            ctx["period"] = f.period.toKey()
            ctx["periodProgressRatio"] = f.periodProgressRatio
            return ctx
        }
    }
}
