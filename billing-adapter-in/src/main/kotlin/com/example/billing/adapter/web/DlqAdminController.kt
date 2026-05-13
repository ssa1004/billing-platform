package com.example.billing.adapter.web

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.serialization.StringDeserializer
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * DLQ 관리 endpoint — 운영자가 .DLT topic 의 메시지를 원본 topic 으로 재처리.
 *
 * 보안: ADMIN 역할만 호출 가능 (PreAuthorize). 운영에서는 Pod-level NetworkPolicy 로 추가 격리.
 */
@RestController
@RequestMapping("/admin/dlq")
@Tag(name = "admin-dlq", description = "DLQ 메시지 재처리 (관리자 전용)")
@ConditionalOnProperty(name = ["billing.outbox.relay.enabled"], havingValue = "true")
class DlqAdminController(
    private val kafkaTemplate: KafkaTemplate<String, String>,
    @Value("\${spring.kafka.bootstrap-servers}") private val bootstrap: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @PostMapping("/replay")
    @PreAuthorize("hasRole('admin')")
    @Operation(summary = "DLT topic 의 모든 메시지를 원본 topic 으로 재발행")
    fun replay(
        @RequestParam dltTopic: String,
        @RequestParam(defaultValue = "100") max: Int,
    ): Map<String, Any> {
        require(dltTopic.endsWith(".DLT")) { "topic 은 .DLT suffix 가 있어야 함" }
        val boundedMax = max.coerceIn(1, MAX_REPLAY)
        val originalTopic = dltTopic.removeSuffix(".DLT")

        val props = mapOf(
            "bootstrap.servers" to bootstrap,
            "group.id" to "dlq-replay-${UUID.randomUUID()}",
            "key.deserializer" to StringDeserializer::class.qualifiedName,
            "value.deserializer" to StringDeserializer::class.qualifiedName,
            "auto.offset.reset" to "earliest",
            "enable.auto.commit" to "false",
        )

        var replayed = 0
        var failed = 0
        KafkaConsumer<String, String>(props).use { consumer ->
            consumer.subscribe(listOf(dltTopic))
            val records = consumer.poll(Duration.ofSeconds(5))
            records.take(boundedMax).forEach { record ->
                // send 는 Future 반환 — get(timeout) 로 동기 확인. 실패한 메시지는 카운트만
                // 분리하고 다음 record 진행. fire-and-forget 이면 broker 가 죽어도 200 OK 가
                // 떨어져 운영자가 메시지 유실을 모름.
                try {
                    kafkaTemplate.send(originalTopic, record.key(), record.value())
                        .get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    replayed++
                } catch (e: Exception) {
                    failed++
                    log.warn("DLQ replay failed: topic={} key={} reason={}",
                        originalTopic, record.key(), e.message)
                }
            }
            log.info("DLQ replay: from {} → {} success={} failed={}",
                dltTopic, originalTopic, replayed, failed)
        }

        return mapOf(
            "from" to dltTopic,
            "to" to originalTopic,
            "replayed" to replayed,
            "failed" to failed,
        )
    }

    companion object {
        private const val SEND_TIMEOUT_SECONDS = 10L
        /** OWASP API4 — replay 한 번에 가져갈 수 있는 메시지 상한. */
        private const val MAX_REPLAY = 1000
    }
}
