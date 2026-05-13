package com.example.billing.domain.metering

import com.example.billing.domain.settlement.BillingPeriod
import com.example.billing.domain.shared.CustomerId
import java.time.Instant
import java.util.UUID

/**
 * 특정 기간 (BillingPeriod) 의 특정 customer × resourceType 사용량 집계 결과.
 *
 * 일/시간 단위 rollup 도 동일 구조로 표현 (period 만 다름). 월별 집계가 결국 청구서의 한
 * line 이 된다.
 *
 * 모든 필드가 final 이지만 생성자에 totalQuantity / eventCount 음수 검증이 있어 data
 * class 가 아닌 일반 class + private 생성자 + companion factory 패턴 유지. record-style
 * accessor 는 `@get:JvmName` 으로 보존.
 */
class AggregatedUsage private constructor(
    @get:JvmName("id") val id: UUID,
    @get:JvmName("customerId") val customerId: CustomerId,
    @get:JvmName("resourceType") val resourceType: ResourceType,
    @get:JvmName("period") val period: BillingPeriod,
    @get:JvmName("totalQuantity") val totalQuantity: Long,
    @get:JvmName("eventCount") val eventCount: Long,
    @get:JvmName("aggregatedAt") val aggregatedAt: Instant,
) {

    init {
        require(totalQuantity >= 0) { "totalQuantity must be non-negative" }
        require(eventCount >= 0) { "eventCount must be non-negative" }
    }

    companion object {
        @JvmStatic
        fun of(
            customerId: CustomerId,
            resourceType: ResourceType,
            period: BillingPeriod,
            totalQuantity: Long,
            eventCount: Long,
            aggregatedAt: Instant,
        ): AggregatedUsage = AggregatedUsage(
            UUID.randomUUID(),
            customerId,
            resourceType,
            period,
            totalQuantity,
            eventCount,
            aggregatedAt,
        )

        /** 영속 계층 (DB) 에서 읽어와 도메인 객체로 복원할 때만 호출 — 일반 코드는 [of] 사용. */
        @JvmStatic
        fun restore(
            id: UUID,
            customerId: CustomerId,
            resourceType: ResourceType,
            period: BillingPeriod,
            totalQuantity: Long,
            eventCount: Long,
            aggregatedAt: Instant,
        ): AggregatedUsage = AggregatedUsage(
            id,
            customerId,
            resourceType,
            period,
            totalQuantity,
            eventCount,
            aggregatedAt,
        )
    }
}
