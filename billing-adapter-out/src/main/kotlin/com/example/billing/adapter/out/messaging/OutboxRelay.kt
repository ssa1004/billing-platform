package com.example.billing.adapter.out.messaging

import com.example.billing.adapter.out.persistence.outbox.OutboxJpaEntity
import com.example.billing.adapter.out.persistence.outbox.OutboxRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.data.domain.PageRequest
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Outbox 테이블 → Kafka 로 메시지를 옮기는 relay (전달자).
 *
 * 전체 그림: 도메인 트랜잭션은 outbox 테이블에 INSERT 만 하고 끝납니다 (DB commit 과
 * 이벤트 발행을 한 트랜잭션에 묶기 위해 — ADR-0005). 이 relay 가 별도 polling 으로 unpublished
 * row 를 꺼내 Kafka 로 send 한 뒤 markPublished 로 표시합니다. `billing.outbox.relay.enabled=true`
 * 일 때만 활성.
 *
 * 여러 인스턴스에서 동시에 돌려도 안전한 이유 (SKIP LOCKED):
 * [OutboxRepository.findUnpublished] 가 `PESSIMISTIC_WRITE + lock.timeout=0` 으로
 * 잡혀 있어 Postgres `FOR UPDATE SKIP LOCKED` 로 변환됩니다 — 다른 인스턴스가 이미
 * lock 잡은 row 는 결과에서 제외되어 같은 row 를 두 워커가 동시에 잡는 일이 없습니다 (ShedLock
 * 같은 추가 분산 lock 불필요). 단, 락은 트랜잭션이 닫혀야 풀리므로 fetch + send +
 * markPublished 가 모두 같은 트랜잭션 안에서 끝나야 합니다.
 *
 * 왜 메시지 1건마다 별도 트랜잭션 (REQUIRES_NEW):
 *  - 한 메시지의 Kafka send 가 sendTimeoutMs 까지 블록되더라도 그 트랜잭션 하나만 멈춤.
 *    다른 메시지의 트랜잭션은 독립이라 DB connection pool 전체가 묶이지 않습니다.
 *  - 한 메시지가 실패해도 다른 메시지의 markPublished 는 그대로 commit — batch 안의
 *    다른 메시지가 같이 rollback 되는 상황 방지.
 *  - SKIP LOCKED 가 동작하려면 lock 이 가능한 한 빨리 풀려야 → 트랜잭션을 짧게.
 * 한 polling cycle 에서 batch_size 만큼 메시지를 처리하지만 각 메시지마다 새 트랜잭션.
 *
 * 실패 처리 — at-least-once (최소 한 번 전달, 중복 가능):
 * Kafka send 가 실패하거나 timeout 이면 markPublished 를 호출하지 않고 트랜잭션을 그대로
 * commit (예외는 catch 됨) → lock 이 해제되고, 다음 polling 에서 같은 row 를 다시 시도합니다.
 * 컨슈머는 같은 메시지가 여러 번 도착해도 결과가 같아야 합니다 (멱등 처리). 계속 실패하는
 * 메시지 (poison pill, 어떤 컨슈머도 처리할 수 없는 메시지) 는 별도 백로그 / DLQ 로 분리.
 */
@Component
@ConditionalOnProperty(name = ["billing.outbox.relay.enabled"], havingValue = "true")
open class OutboxRelay(
    private val outboxRepository: OutboxRepository,
    private val kafkaTemplate: KafkaTemplate<String, String>,
    private val clock: Clock,
) {

    @Value("\${billing.outbox.relay.batch-size:100}")
    private var batchSize: Int = 100

    @Value("\${billing.outbox.relay.send-timeout-ms:5000}")
    private var sendTimeoutMs: Long = 5000

    @Value("\${billing.outbox.relay.topic-prefix:billing.}")
    private var topicPrefix: String = "billing."

    /**
     * 폴링 진입점 — batchSize 만큼 메시지를 메시지 단위 트랜잭션으로 처리. 한 메시지가
     * 실패해도 격리되어 다음 메시지가 진행됨. SKIP LOCKED 덕분에 다른 워커가 잡고 있는
     * 메시지는 자동으로 제외.
     */
    @Scheduled(fixedDelayString = "\${billing.outbox.relay.poll-interval-ms:1000}")
    fun publishPending() {
        var published = 0
        var attempted = 0
        for (i in 0 until batchSize) {
            // 한 메시지 처리 중 예상 못한 RuntimeException 이 발생해도 전체 polling 이
            // 죽지 않도록 격리. 다음 polling 에서 같은 메시지를 다시 시도 (at-least-once).
            try {
                val result = publishNext()
                if (result == ProcessResult.NONE) break
                attempted++
                if (result == ProcessResult.PUBLISHED) published++
            } catch (e: RuntimeException) {
                attempted++
                log.warn("outbox relay unexpected error skipping", e)
            }
        }
        if (attempted > 0) log.debug("outbox relay published {}/{}", published, attempted)
    }

    enum class ProcessResult { PUBLISHED, FAILED, NONE }

    /**
     * 한 메시지 단위 트랜잭션 — fetch (SKIP LOCKED) + Kafka send + markPublished.
     *
     * send 가 실패하거나 timeout 이면 markPublished 가 호출되지 않은 채 트랜잭션이 commit
     * 됩니다 (예외는 [publishPending] 가 잡음) → lock 해제, 다음 polling 에서 재시도.
     *
     * 왜 fetch + send + markPublished 가 한 트랜잭션이어야 하는가: SKIP LOCKED 가
     * "이 row 는 다른 워커가 잡고 있다" 라고 판단하는 근거는 살아 있는 트랜잭션의 row lock
     * 입니다. 트랜잭션이 commit/rollback 되면 lock 도 풀려 다른 워커가 같은 row 를 잡을 수
     * 있게 됩니다. fetch 와 markPublished 를 다른 트랜잭션으로 쪼개면 send 동안 lock 이
     * 풀려 두 워커가 같은 메시지를 발행할 수 있습니다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    open fun publishNext(): ProcessResult {
        val head = outboxRepository.findUnpublished(PageRequest.of(0, 1))
        if (head.isEmpty()) return ProcessResult.NONE
        val msg = head[0]
        if (sendToKafka(msg)) {
            outboxRepository.markPublished(msg.id!!, clock.instant())
            return ProcessResult.PUBLISHED
        }
        return ProcessResult.FAILED
    }

    private fun sendToKafka(msg: OutboxJpaEntity): Boolean {
        val topic = topicPrefix + msg.aggregateType.lowercase() + "." + msg.eventType.lowercase()
        try {
            kafkaTemplate.send(topic, msg.aggregateId, msg.payload)
                .get(sendTimeoutMs, TimeUnit.MILLISECONDS)
            return true
        } catch (e: TimeoutException) {
            log.warn("kafka publish timeout id={} topic={}", msg.id, topic)
        } catch (e: ExecutionException) {
            log.warn(
                "kafka publish failed id={} topic={} reason={}",
                msg.id, topic,
                e.cause?.message ?: e.message,
            )
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            log.warn("kafka publish interrupted id={}", msg.id)
        }
        return false
    }

    companion object {
        private val log = LoggerFactory.getLogger(OutboxRelay::class.java)
    }
}
