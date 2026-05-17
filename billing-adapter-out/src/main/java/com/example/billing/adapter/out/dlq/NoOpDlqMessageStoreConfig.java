package com.example.billing.adapter.out.dlq;

import com.example.billing.application.dto.DlqMessageDetail;
import com.example.billing.application.dto.DlqMessageFilter;
import com.example.billing.application.dto.DlqMessageView;
import com.example.billing.application.exception.IllegalDlqOperationException;
import com.example.billing.application.port.out.DlqMessageStore;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Kafka outbox relay 가 비활성인 (test / dev) 환경의 fallback {@link DlqMessageStore} — 항상 빈
 * 결과. {@link KafkaDlqMessageStore} 가 등록되지 않은 경우에만 활성.
 *
 * <p>운영에서는 {@code billing.outbox.relay.enabled=true} 로 {@link KafkaDlqMessageStore} 가 켜져야
 * 한다. fallback 으로 떨어지면 list / search / stats 가 항상 0건, write 작업은 메시지 없음으로
 * 거절.
 */
@Configuration
public class NoOpDlqMessageStoreConfig {

    @Bean
    @ConditionalOnMissingBean(DlqMessageStore.class)
    public DlqMessageStore dlqMessageStore() {
        return new NoOp();
    }

    static final class NoOp implements DlqMessageStore {

        @Override
        public List<DlqMessageView> search(DlqMessageFilter filter, String cursor, int size) {
            return List.of();
        }

        @Override
        public long count(DlqMessageFilter filter) {
            return 0L;
        }

        @Override
        public Optional<DlqMessageDetail> findDetail(String messageId) {
            return Optional.empty();
        }

        @Override
        public DlqMessageDetail replay(String messageId) {
            throw new IllegalDlqOperationException(
                    "DLQ is disabled (outbox relay off) — cannot replay: " + messageId);
        }

        @Override
        public DlqMessageDetail discard(String messageId, String reason) {
            throw new IllegalDlqOperationException(
                    "DLQ is disabled (outbox relay off) — cannot discard: " + messageId);
        }

        @Override
        public List<StatsRow> aggregateStats(
                DlqMessageFilter filter, Instant from, Instant to, Duration bucket) {
            return List.of();
        }
    }
}
