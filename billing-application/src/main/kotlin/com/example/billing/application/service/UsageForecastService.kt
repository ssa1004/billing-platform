package com.example.billing.application.service

import com.example.billing.application.exception.PricingPlanNotFoundException
import com.example.billing.application.port.`in`.UsageForecastUseCase
import com.example.billing.application.port.out.AggregatedUsageRepository
import com.example.billing.application.port.out.PricingPlanRepository
import com.example.billing.domain.metering.UsageForecast
import com.example.billing.domain.metering.UsageForecast.ResourceForecast
import com.example.billing.domain.pricing.PricingPlan
import com.example.billing.domain.settlement.BillingPeriod
import com.example.billing.domain.shared.CustomerId
import com.example.billing.domain.shared.Money
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Currency

/**
 * 월말 사용량 / 비용 예상 (read model).
 *
 * 알고리즘: 월 진행률 기반 선형 외삽.
 * ```
 * progress = (asOf - periodStart) / (periodEnd - periodStart)   ∈ (0, 1]
 * projectedQuantity = mtdQuantity / progress
 * projectedCost     = pricingPlan.calculate(projectedQuantity)
 * ```
 *
 * 한계:
 *  - 주기성 (예: 평일/주말 패턴) 무시 — 7일 평균 / time-series 모델로 개선 가능
 *  - plan 변경이 월 중간에 발생하면 부정확 — 현재 시점 plan 으로만 계산
 *  - 월 초 (progress < 5%) 에는 외삽 신뢰도 낮음 — 화면 측에서 progress 노출하여 처리
 *
 * 현재 plan 이 없는 customer 는 [PricingPlanNotFoundException]. 무료 tier 만
 * 쓰는 고객도 plan 정의는 필요 (전 tier 가격 0원이라도).
 */
@Service
open class UsageForecastService(
    private val aggregatedUsages: AggregatedUsageRepository,
    private val pricingPlans: PricingPlanRepository,
    private val clock: Clock,
) : UsageForecastUseCase {

    @Transactional(readOnly = true)
    override fun forecastCurrentPeriod(customerId: CustomerId): UsageForecast {
        val asOf = clock.instant()
        val period = BillingPeriod.containing(asOf)

        val plan = pricingPlans.findEffective(customerId, asOf)
            .orElseThrow { PricingPlanNotFoundException(customerId, asOf) }

        val progress = computeProgress(period, asOf)
        val mtd = aggregatedUsages.findByCustomerAndPeriod(customerId, period)

        val resources = ArrayList<ResourceForecast>(mtd.size)
        var totalProjected = Money.zero(currencyOf(plan))

        for (usage in mtd) {
            val mtdQty = usage.totalQuantity
            val projectedQty = projectQuantity(mtdQty, progress)
            val mtdCost = plan.calculate(usage.resourceType, mtdQty)
            val projectedCost = plan.calculate(usage.resourceType, projectedQty)
            resources.add(
                ResourceForecast(
                    usage.resourceType, mtdQty, projectedQty, mtdCost, projectedCost,
                ),
            )
            totalProjected = totalProjected.add(projectedCost)
        }

        log.debug(
            "forecast customer={} period={} progress={} resources={} total={}",
            customerId, period, progress, resources.size, totalProjected,
        )
        return UsageForecast(customerId, period, asOf, progress, resources, totalProjected)
    }

    private fun computeProgress(period: BillingPeriod, asOf: Instant): Double {
        val start = period.fromInclusive()
        val end = period.toExclusive()
        if (!asOf.isAfter(start)) return 0.0
        if (!asOf.isBefore(end)) return 1.0
        val elapsed = Duration.between(start, asOf).toMillis()
        val total = Duration.between(start, end).toMillis()
        return elapsed.toDouble() / total
    }

    private fun projectQuantity(mtdQuantity: Long, progress: Double): Long {
        if (progress <= MIN_PROGRESS_FOR_EXTRAPOLATION) return mtdQuantity // 신뢰도 너무 낮음
        if (progress >= 1.0) return mtdQuantity // 기간 종료
        return Math.round(mtdQuantity / progress)
    }

    companion object {
        private val log = LoggerFactory.getLogger(UsageForecastService::class.java)

        /** 외삽 분모가 0 에 너무 가까우면 폭발. 이 미만이면 외삽 안 함 (mtd == projected). */
        private const val MIN_PROGRESS_FOR_EXTRAPOLATION = 0.001

        private fun currencyOf(plan: PricingPlan): Currency {
            // PricingPlan 의 어떤 tier 든 같은 통화 — 첫 tier 통화 사용
            return plan.tiers.first().unitPrice.currency
        }
    }
}
