package com.example.billing.domain.metering

import com.example.billing.domain.shared.CustomerId
import java.time.Instant
import java.util.UUID

/**
 * 사용량 이벤트 — append-only, immutable.
 *
 * 외부 (API 게이트웨이, SDK) 에서 푸시하는 측정 이벤트. eventId 는 클라이언트가 생성하며
 * 멱등성 키 역할을 한다 (동일 eventId 재수신 시 무시).
 *
 * [ResourceType] 별로 unit 이 정해져 있다 (API 호출은 count, 스토리지는 byte_hour 등).
 * 집계 단계에서 unit 별로 합산되어 [AggregatedUsage] 로 변환된다.
 *
 * 모든 필드가 final 이지만 생성자에 quantity 음수 검증이 있어 data class 가 아닌 일반
 * class + private 생성자 + companion factory 패턴 유지. record-style accessor 는
 * `@get:JvmName` 으로 보존.
 */
class UsageEvent private constructor(
    @get:JvmName("eventId") val eventId: UUID,
    @get:JvmName("customerId") val customerId: CustomerId,
    @get:JvmName("resourceType") val resourceType: ResourceType,
    @get:JvmName("quantity") val quantity: Long,
    @get:JvmName("occurredAt") val occurredAt: Instant,
    @get:JvmName("receivedAt") val receivedAt: Instant,
) {

    init {
        require(quantity >= 0) { "quantity must be non-negative: $quantity" }
    }

    companion object {
        @JvmStatic
        fun record(
            eventId: UUID,
            customerId: CustomerId,
            resourceType: ResourceType,
            quantity: Long,
            occurredAt: Instant,
            receivedAt: Instant,
        ): UsageEvent = UsageEvent(eventId, customerId, resourceType, quantity, occurredAt, receivedAt)

        /** 영속 계층 (DB) 에서 읽어와 도메인 객체로 복원할 때만 호출 — 일반 코드는 [record] 사용. */
        @JvmStatic
        fun restore(
            eventId: UUID,
            customerId: CustomerId,
            resourceType: ResourceType,
            quantity: Long,
            occurredAt: Instant,
            receivedAt: Instant,
        ): UsageEvent = UsageEvent(eventId, customerId, resourceType, quantity, occurredAt, receivedAt)
    }
}
