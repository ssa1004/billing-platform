package com.example.billing.adapter.out.messaging

import com.example.billing.adapter.out.persistence.outbox.OutboxJpaEntity
import com.example.billing.adapter.out.persistence.outbox.OutboxRepository
import com.example.billing.application.port.out.EventPublisher
import com.example.billing.domain.shared.DomainEvent
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

/**
 * [EventPublisher] 의 outbox 구현 — 도메인 트랜잭션 안에서 outbox 테이블에 INSERT 만
 * 합니다. 실제 Kafka publish 는 별도 polling 워커 ([OutboxRelay]) 가 처리합니다.
 *
 * 왜 이렇게 분리하는가 (outbox 패턴): DB commit 과 Kafka publish 를 한 트랜잭션에
 * 안전하게 묶기 위해서. 도메인 변경 commit 직후에 Kafka 로 직접 send 하면, send 는 성공했는데
 * 직전 DB commit 이 실제로는 안 됐다거나 그 반대 — 두 시스템 사이가 어긋나는 dual-write 문제가
 * 생깁니다. outbox 테이블에 INSERT 만 하고 같은 트랜잭션에서 commit/rollback 하면 도메인
 * 변경과 "발행 의도" 가 원자적으로 묶입니다 (ADR-0005).
 *
 * `Propagation.MANDATORY` 가 강제하는 것: 호출자가 트랜잭션을 열어둔 상태에서만
 * publish 가 호출되도록 런타임에 강제 합니다. 만약 호출자가 깜빡하고 `@Transactional`
 * 없이 publish 만 부르면 즉시 예외가 떨어져, "도메인 변경은 commit 됐는데 outbox INSERT 는
 * 별도 트랜잭션이라 시점이 어긋남" 같은 정합 사고를 사전 차단합니다. 컴파일타임 검증은 아니지만
 * 첫 호출에서 바로 fail-fast.
 */
@Component
class OutboxEventPublisher(
    private val outboxRepository: OutboxRepository,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
) : EventPublisher {

    @Transactional(propagation = Propagation.MANDATORY)
    override fun publish(event: DomainEvent) {
        try {
            val aggregateType = inferAggregateType(event)
            val msg = OutboxJpaEntity(
                id = UUID.randomUUID(),
                aggregateType = aggregateType,
                aggregateId = event.aggregateId(),
                eventType = event.javaClass.simpleName,
                payload = objectMapper.writeValueAsString(event),
                createdAt = clock.instant(),
                publishedAt = null,
            )
            outboxRepository.save(msg)
        } catch (e: JsonProcessingException) {
            throw IllegalStateException("failed to serialize event " + event.javaClass.name, e)
        }
    }

    private fun inferAggregateType(event: DomainEvent): String {
        // 이벤트 클래스의 패키지로 aggregate 타입 추론 (예: WalletEvents$WalletDeposited → Wallet).
        // 새 도메인을 추가하면 여기에 등록 — 누락 시 Kafka topic 이 billing.unknown. 로 잘못
        // 라우팅됩니다. 누락이 디버깅에서 빨리 보이도록 IllegalStateException 으로 fail-fast.
        val fqn = event.javaClass.name
        if (fqn.contains(".wallet.")) return "Wallet"
        if (fqn.contains(".order.")) return "Order"
        if (fqn.contains(".payment.")) return "Payment"
        if (fqn.contains(".refund.")) return "Refund"
        if (fqn.contains(".credit.")) return "Credit"
        if (fqn.contains(".budget.")) return "Budget"
        if (fqn.contains(".invoice.")) return "Invoice"
        if (fqn.contains(".webhook.")) return "Webhook"
        throw IllegalStateException(
            "unknown aggregate type for event $fqn" +
                " — register it in OutboxEventPublisher.inferAggregateType",
        )
    }
}
