package com.example.billing.adapter.out.messaging;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.stereotype.Component;
import org.springframework.util.backoff.ExponentialBackOff;

import java.util.function.BiFunction;

/**
 * Kafka 컨슈머 실패 시 DLQ 로 전송하는 ErrorHandler factory.
 *
 * <p>흐름: 컨슈머 N회 실패 (exponential backoff) → DLQ topic 으로 전송. DLQ topic 이름은
 * 원본 topic 에 {@code .DLT} suffix 가 붙음 (Spring Kafka 컨벤션).</p>
 *
 * <p>운영자는 DLQ consumer 또는 재처리 endpoint 로 메시지를 원본 topic 에 다시 publish 하여 복구.</p>
 */
@Component
@ConditionalOnProperty(name = "billing.outbox.relay.enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class DlqHandler {

    private final KafkaTemplate<String, String> kafkaTemplate;

    public DefaultErrorHandler errorHandler() {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kafkaTemplate,
                (BiFunction<ConsumerRecord<?, ?>, Exception, org.apache.kafka.common.TopicPartition>)
                (record, ex) -> {
                    log.warn("sending to DLQ topic={} key={} reason={}",
                            record.topic() + ".DLT", record.key(), ex.getMessage());
                    return new org.apache.kafka.common.TopicPartition(record.topic() + ".DLT", record.partition());
                }
        );

        // 3회 재시도, 200ms → 400ms → 800ms exponential backoff
        ExponentialBackOff backOff = new ExponentialBackOff(200, 2.0);
        backOff.setMaxInterval(2000);
        backOff.setMaxElapsedTime(5000);
        return new DefaultErrorHandler(recoverer, backOff);
    }
}
