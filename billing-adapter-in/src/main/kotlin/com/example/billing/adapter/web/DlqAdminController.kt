package com.example.billing.adapter.web

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.TopicPartition
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

/**
 * DLQ 관리 endpoint — 운영자가 .DLT topic 의 메시지를 원본 topic 으로 재처리.
 *
 * 보안: ADMIN 역할만 호출 가능 (PreAuthorize). 운영에서는 Pod-level NetworkPolicy 로 추가 격리.
 */
@RestController
@RequestMapping("/admin/dlq")
@Tag(name = "admin-dlq", description = "DLQ 메시지 재처리 (관리자 전용)")
@ConditionalOnProperty(name = ["wallet.outbox.relay.enabled"], havingValue = "true")
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
        KafkaConsumer<String, String>(props).use { consumer ->
            consumer.subscribe(listOf(dltTopic))
            val records = consumer.poll(Duration.ofSeconds(5))
            records.take(max).forEach { record ->
                kafkaTemplate.send(originalTopic, record.key(), record.value())
                replayed++
            }
            log.info("DLQ replay: from {} → {} count={}", dltTopic, originalTopic, replayed)
        }

        return mapOf(
            "from" to dltTopic,
            "to" to originalTopic,
            "replayed" to replayed,
        )
    }
}
