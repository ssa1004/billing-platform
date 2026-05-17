package com.example.billing.adapter.out.messaging

import com.example.billing.adapter.out.persistence.outbox.OutboxJpaEntity
import com.example.billing.adapter.out.persistence.outbox.OutboxRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.data.domain.Pageable
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.support.SendResult
import org.springframework.test.util.ReflectionTestUtils
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CompletableFuture

/**
 * OutboxRelay 단위 테스트 — 정상 발행 / 부분 실패 / 예상 못한 RuntimeException 격리.
 *
 * 핵심 invariant: 한 메시지가 죽어도 같은 batch 의 다른 메시지는 진행되어야 한다.
 * 죽은 메시지는 markPublished 가 안 되므로 다음 polling 에서 재시도 (at-least-once).
 */
@ExtendWith(MockitoExtension::class)
class OutboxRelayTest {

    @Mock
    lateinit var outboxRepository: OutboxRepository

    @Mock
    lateinit var kafkaTemplate: KafkaTemplate<String, String>

    lateinit var relay: OutboxRelay

    @BeforeEach
    fun setUp() {
        relay = OutboxRelay(outboxRepository, kafkaTemplate, CLOCK)
        ReflectionTestUtils.setField(relay, "batchSize", 100)
        ReflectionTestUtils.setField(relay, "sendTimeoutMs", 1000L)
        ReflectionTestUtils.setField(relay, "topicPrefix", "billing.")
    }

    @Test
    fun emptyBatch_noWork() {
        whenever(outboxRepository.findUnpublished(any<Pageable>())).thenReturn(emptyList())

        relay.publishPending()

        verify(kafkaTemplate, never()).send(any<String>(), any<String>(), any<String>())
    }

    @Test
    fun successfulSend_marksPublished() {
        val m = msg("Order", "Placed")
        // 한 메시지씩 SKIP LOCKED 로 픽업 → 두 번째 호출은 비어 있음
        whenever(outboxRepository.findUnpublished(any<Pageable>()))
            .thenReturn(listOf(m))
            .thenReturn(emptyList())
        whenever(kafkaTemplate.send(eq("billing.order.placed"), eq("agg-1"), eq("{}")))
            .thenReturn(CompletableFuture.completedFuture(null as SendResult<String, String>?))

        relay.publishPending()

        verify(outboxRepository).markPublished(eq(m.id!!), eq(NOW))
    }

    @Test
    fun runtimeException_isolatedToSingleMessage_otherStillPublishes() {
        val poison = msg("Order", "Placed")
        val good = msg("Order", "Paid")
        // SKIP LOCKED 로 한 row 씩 fetch — poison → good → empty 순서
        whenever(outboxRepository.findUnpublished(any<Pageable>()))
            .thenReturn(listOf(poison))
            .thenReturn(listOf(good))
            .thenReturn(emptyList())
        // poison 메시지는 send 시점에 직렬화 / 설정 등으로 RuntimeException
        whenever(kafkaTemplate.send(eq("billing.order.placed"), eq("agg-1"), eq("{}")))
            .thenThrow(IllegalStateException("serialization broken"))
        // good 은 정상
        whenever(kafkaTemplate.send(eq("billing.order.paid"), eq("agg-1"), eq("{}")))
            .thenReturn(CompletableFuture.completedFuture(null as SendResult<String, String>?))

        relay.publishPending()

        // poison 은 markPublished 안 됨 (다음 poll 에서 재시도), good 은 markPublished 됨.
        verify(outboxRepository, never()).markPublished(eq(poison.id!!), anyOrNull())
        verify(outboxRepository, times(1)).markPublished(eq(good.id!!), eq(NOW))
    }

    @Test
    fun kafkaSendReturnsFailedFuture_skipsMarkPublished() {
        val m = msg("Payment", "Approved")
        whenever(outboxRepository.findUnpublished(any<Pageable>()))
            .thenReturn(listOf(m))
            .thenReturn(emptyList())

        val failed = CompletableFuture<SendResult<String, String>>()
        failed.completeExceptionally(RuntimeException("broker down"))
        whenever(kafkaTemplate.send(eq("billing.payment.approved"), eq("agg-1"), eq("{}")))
            .thenReturn(failed)

        relay.publishPending()

        verify(outboxRepository, never()).markPublished(any<UUID>(), anyOrNull())
    }

    companion object {
        private val NOW: Instant = Instant.parse("2026-05-07T00:00:00Z")
        private val CLOCK: Clock = Clock.fixed(NOW, ZoneOffset.UTC)

        private fun msg(aggregateType: String, eventType: String): OutboxJpaEntity {
            val m = OutboxJpaEntity()
            m.id = UUID.randomUUID()
            m.aggregateType = aggregateType
            m.aggregateId = "agg-1"
            m.eventType = eventType
            m.payload = "{}"
            m.createdAt = NOW
            return m
        }
    }
}
