package com.example.billing.adapter.out.messaging

import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.common.TopicPartition
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.listener.DefaultErrorHandler
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer
import org.springframework.stereotype.Component
import org.springframework.util.backoff.ExponentialBackOff

/**
 * Kafka 컨슈머 실패 시 DLQ (Dead Letter Queue, 처리 실패한 메시지를 모아두는 별도 큐) 로
 * 전송하는 ErrorHandler 팩토리.
 *
 * 흐름: 컨슈머가 N 회 실패 (간격을 두 배씩 늘리는 exponential backoff) → DLQ topic 으로
 * 전송. DLQ topic 이름은 원본 topic 에 `.DLT` (Dead Letter Topic) 접미사가 붙는 것이
 * Spring Kafka 컨벤션입니다.
 *
 * 운영자는 DLQ 컨슈머 또는 별도 재처리 endpoint 로 DLQ 의 메시지를 원본 topic 에 다시
 * publish 해서 복구합니다.
 */
@Component
@ConditionalOnProperty(name = ["billing.outbox.relay.enabled"], havingValue = "true")
class DlqHandler(
    private val kafkaTemplate: KafkaTemplate<String, String>,
) {

    fun errorHandler(): DefaultErrorHandler {
        val recoverer = DeadLetterPublishingRecoverer(kafkaTemplate) { record: ConsumerRecord<*, *>, ex: Exception ->
            log.warn(
                "sending to DLQ topic={} key={} reason={}",
                record.topic() + ".DLT", record.key(), ex.message,
            )
            TopicPartition(record.topic() + ".DLT", record.partition())
        }

        // 3회 재시도, 200ms → 400ms → 800ms exponential backoff
        val backOff = ExponentialBackOff(200, 2.0)
        backOff.maxInterval = 2000
        backOff.maxElapsedTime = 5000
        return DefaultErrorHandler(recoverer, backOff)
    }

    companion object {
        private val log = LoggerFactory.getLogger(DlqHandler::class.java)
    }
}
