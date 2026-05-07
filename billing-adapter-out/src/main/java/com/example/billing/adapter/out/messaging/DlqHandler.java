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
 * Kafka 컨슈머 실패 시 DLQ (Dead Letter Queue, 처리 실패한 메시지를 모아두는 별도 큐) 로
 * 전송하는 ErrorHandler 팩토리.
 *
 * <p>흐름: 컨슈머가 N 회 실패 (간격을 두 배씩 늘리는 exponential backoff) → DLQ topic 으로
 * 전송. DLQ topic 이름은 원본 topic 에 {@code .DLT} (Dead Letter Topic) 접미사가 붙는 것이
 * Spring Kafka 컨벤션입니다.</p>
 *
 * <p>운영자는 DLQ 컨슈머 또는 별도 재처리 endpoint 로 DLQ 의 메시지를 원본 topic 에 다시
 * publish 해서 복구합니다.</p>
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
