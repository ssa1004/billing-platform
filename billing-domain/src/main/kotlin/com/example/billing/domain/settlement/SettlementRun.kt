package com.example.billing.domain.settlement

import com.example.billing.domain.shared.CustomerId
import java.time.Clock
import java.time.Instant
import java.util.Optional
import java.util.UUID

/**
 * 정산 실행 entity. 한 BillingPeriod × 한 CustomerId 의 정산 1회 실행을 추적.
 *
 * `customerId` 가 null 인 행은 "전체 고객 정산" 메타 row 로, 통계 보고용. 실제 처리는
 * customer 별 row 단위로 이뤄짐.
 *
 * 상태:
 * - PENDING — 큐에 들어감
 * - RUNNING — worker 가 잡음
 * - COMPLETED — 청구서 발행 + 결제 시도 모두 끝남 (결제 결과는 별개로 추적)
 * - FAILED — 복구 불가능한 에러 발생, 운영자 개입 필요
 *
 * record-style accessor (id(), period() 등) 는 `@get:JvmName` 으로 Java/Kotlin 양쪽
 * 호출자 호환 유지. customerId() 만 nullable 내부 상태를 Optional 로 노출 — 기존 Java
 * 호출자 (JpaSettlementRunRepositoryAdapter 의 `.customerId().map(CustomerId::value).orElse(null)`)
 * 를 그대로 유지하기 위함.
 */
class SettlementRun private constructor(
    @get:JvmName("id") val id: UUID,
    @get:JvmName("period") val period: BillingPeriod,
    private val customerIdOrNull: CustomerId?,
    status: SettlementStatus,
    startedAt: Instant?,
    finishedAt: Instant?,
    invoicesGenerated: Int,
    paymentsAttempted: Int,
    paymentsSucceeded: Int,
    failureReason: String?,
    @get:JvmName("createdAt") val createdAt: Instant,
    @get:JvmName("version") val version: Long,
) {

    @get:JvmName("status")
    var status: SettlementStatus = status
        private set

    @get:JvmName("startedAt")
    var startedAt: Instant? = startedAt
        private set

    @get:JvmName("finishedAt")
    var finishedAt: Instant? = finishedAt
        private set

    @get:JvmName("invoicesGenerated")
    var invoicesGenerated: Int = invoicesGenerated
        private set

    @get:JvmName("paymentsAttempted")
    var paymentsAttempted: Int = paymentsAttempted
        private set

    @get:JvmName("paymentsSucceeded")
    var paymentsSucceeded: Int = paymentsSucceeded
        private set

    @get:JvmName("failureReason")
    var failureReason: String? = failureReason
        private set

    fun customerId(): Optional<CustomerId> = Optional.ofNullable(customerIdOrNull)

    fun start(clock: Clock) {
        check(status == SettlementStatus.PENDING) { "cannot start from status $status" }
        this.status = SettlementStatus.RUNNING
        this.startedAt = clock.instant()
    }

    fun recordResult(
        invoicesGenerated: Int,
        paymentsAttempted: Int,
        paymentsSucceeded: Int,
        clock: Clock,
    ) {
        check(status == SettlementStatus.RUNNING) { "cannot record result from status $status" }
        this.invoicesGenerated = invoicesGenerated
        this.paymentsAttempted = paymentsAttempted
        this.paymentsSucceeded = paymentsSucceeded
        this.status = SettlementStatus.COMPLETED
        this.finishedAt = clock.instant()
    }

    fun fail(reason: String, clock: Clock) {
        check(status != SettlementStatus.COMPLETED) { "cannot fail completed run" }
        this.status = SettlementStatus.FAILED
        this.failureReason = reason
        this.finishedAt = clock.instant()
    }

    companion object {
        @JvmStatic
        fun queue(period: BillingPeriod, customerId: CustomerId?, clock: Clock): SettlementRun =
            SettlementRun(
                id = UUID.randomUUID(),
                period = period,
                customerIdOrNull = customerId,
                status = SettlementStatus.PENDING,
                startedAt = null,
                finishedAt = null,
                invoicesGenerated = 0,
                paymentsAttempted = 0,
                paymentsSucceeded = 0,
                failureReason = null,
                createdAt = clock.instant(),
                version = 0L,
            )

        @JvmStatic
        fun restore(
            id: UUID,
            period: BillingPeriod,
            customerId: CustomerId?,
            status: SettlementStatus,
            startedAt: Instant?,
            finishedAt: Instant?,
            invoicesGenerated: Int,
            paymentsAttempted: Int,
            paymentsSucceeded: Int,
            failureReason: String?,
            createdAt: Instant,
            version: Long,
        ): SettlementRun = SettlementRun(
            id = id,
            period = period,
            customerIdOrNull = customerId,
            status = status,
            startedAt = startedAt,
            finishedAt = finishedAt,
            invoicesGenerated = invoicesGenerated,
            paymentsAttempted = paymentsAttempted,
            paymentsSucceeded = paymentsSucceeded,
            failureReason = failureReason,
            createdAt = createdAt,
            version = version,
        )
    }
}
