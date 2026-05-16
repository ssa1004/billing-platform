package com.example.billing.application.service

import com.example.billing.application.port.`in`.AggregateUsageUseCase
import com.example.billing.application.port.out.AggregatedUsageRepository
import com.example.billing.application.port.out.UsageEventRepository
import com.example.billing.domain.metering.AggregatedUsage
import com.example.billing.domain.metering.ResourceType
import com.example.billing.domain.settlement.BillingPeriod
import com.example.billing.domain.shared.CustomerId
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/**
 * UsageEvent → AggregatedUsage rollup. 월말 정산 직전에 실행되는 batch step.
 *
 * 대용량 customer 의 경우 streaming aggregation 으로 확장 가능 (현재는 in-memory grouping).
 * 1개월 × 1 customer 의 이벤트가 100만 건을 넘기 시작하면 chunk-based 처리로 전환 필요.
 */
@Service
open class AggregateUsageService(
    private val usageEventRepository: UsageEventRepository,
    private val aggregatedRepository: AggregatedUsageRepository,
    private val clock: Clock,
) : AggregateUsageUseCase {

    @Transactional
    override fun aggregate(customerId: CustomerId, period: BillingPeriod): Int {
        // 단순화: 전체 fetch 후 in-memory grouping. 대규모는 cursor + chunk 로 전환.
        val events = usageEventRepository.findInRange(
            period.fromInclusive(), period.toExclusive(), FETCH_PAGE_SIZE,
        )

        // [totalQuantity, eventCount]
        val rollup = HashMap<ResourceType, LongArray>()
        for (event in events) {
            if (event.customerId != customerId) continue
            val acc = rollup.getOrPut(event.resourceType) { LongArray(2) }
            acc[0] += event.quantity
            acc[1] += 1
        }

        for ((resourceType, acc) in rollup) {
            val agg = AggregatedUsage.of(
                customerId, resourceType, period,
                acc[0], acc[1], clock.instant(),
            )
            aggregatedRepository.save(agg)
        }
        return rollup.size
    }

    companion object {
        private const val FETCH_PAGE_SIZE = 5000
    }
}
