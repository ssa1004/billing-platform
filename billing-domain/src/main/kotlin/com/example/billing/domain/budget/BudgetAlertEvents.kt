package com.example.billing.domain.budget

import com.example.billing.domain.shared.CustomerId
import com.example.billing.domain.shared.DomainEvent
import com.example.billing.domain.shared.Money
import java.time.Instant

/**
 * BudgetAlertRule 도메인 이벤트.
 *
 * [Triggered] 는 다운스트림 컨슈머가 customer 알림 채널 (email / Slack / webhook) 로 push 하는
 * 진입점입니다.
 *
 * record-style accessor (`ruleId()`, `threshold()`, `occurredAt()` 등) 는 `@get:JvmName` 으로
 * Java/Kotlin 양쪽 호출자에서 그대로 호출 가능 — 기존 Java 호출자 (`ev.ruleId()`,
 * `ev.aggregateId()`) 무변경. data class 의 component 이름 충돌 회피로 `private val
 * occurredAtInstant` + `override fun occurredAt() = it` 패턴 (wallet / credit / payment 와 동일).
 */
object BudgetAlertEvents {

    data class Created(
        @get:JvmName("ruleId") val ruleId: BudgetAlertRuleId,
        @get:JvmName("customerId") val customerId: CustomerId,
        @get:JvmName("threshold") val threshold: Money,
        private val occurredAtInstant: Instant,
    ) : DomainEvent {
        override fun aggregateId(): String = ruleId.toString()
        override fun occurredAt(): Instant = occurredAtInstant
    }

    data class Triggered(
        @get:JvmName("ruleId") val ruleId: BudgetAlertRuleId,
        @get:JvmName("customerId") val customerId: CustomerId,
        @get:JvmName("threshold") val threshold: Money,
        @get:JvmName("projectedCost") val projectedCost: Money,
        /** projectedCost / threshold (1.0 = 정확히 임계, 1.5 = 50% 초과) */
        @get:JvmName("overshootRatio") val overshootRatio: Double,
        private val occurredAtInstant: Instant,
    ) : DomainEvent {
        override fun aggregateId(): String = ruleId.toString()
        override fun occurredAt(): Instant = occurredAtInstant
    }

    data class Paused(
        @get:JvmName("ruleId") val ruleId: BudgetAlertRuleId,
        @get:JvmName("customerId") val customerId: CustomerId,
        private val occurredAtInstant: Instant,
    ) : DomainEvent {
        override fun aggregateId(): String = ruleId.toString()
        override fun occurredAt(): Instant = occurredAtInstant
    }

    data class Resumed(
        @get:JvmName("ruleId") val ruleId: BudgetAlertRuleId,
        @get:JvmName("customerId") val customerId: CustomerId,
        private val occurredAtInstant: Instant,
    ) : DomainEvent {
        override fun aggregateId(): String = ruleId.toString()
        override fun occurredAt(): Instant = occurredAtInstant
    }
}
