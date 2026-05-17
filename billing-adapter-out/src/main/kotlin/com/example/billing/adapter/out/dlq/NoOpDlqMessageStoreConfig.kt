package com.example.billing.adapter.out.dlq

import com.example.billing.application.dto.DlqMessageDetail
import com.example.billing.application.dto.DlqMessageFilter
import com.example.billing.application.dto.DlqMessageView
import com.example.billing.application.exception.IllegalDlqOperationException
import com.example.billing.application.port.out.DlqMessageStore
import com.example.billing.application.port.out.DlqMessageStore.StatsRow
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Duration
import java.time.Instant
import java.util.Optional

/**
 * Kafka outbox relay 가 비활성인 (test / dev) 환경의 fallback [DlqMessageStore] — 항상 빈
 * 결과. [KafkaDlqMessageStore] 가 등록되지 않은 경우에만 활성.
 *
 * 운영에서는 `billing.outbox.relay.enabled=true` 로 [KafkaDlqMessageStore] 가 켜져야
 * 한다. fallback 으로 떨어지면 list / search / stats 가 항상 0건, write 작업은 메시지 없음으로
 * 거절.
 */
@Configuration
class NoOpDlqMessageStoreConfig {

    @Bean
    @ConditionalOnMissingBean(DlqMessageStore::class)
    fun dlqMessageStore(): DlqMessageStore = NoOp()

    internal class NoOp : DlqMessageStore {

        override fun search(filter: DlqMessageFilter, cursor: String?, size: Int): List<DlqMessageView> =
            emptyList()

        override fun count(filter: DlqMessageFilter): Long = 0L

        override fun findDetail(messageId: String): Optional<DlqMessageDetail> = Optional.empty()

        override fun replay(messageId: String): DlqMessageDetail {
            throw IllegalDlqOperationException(
                "DLQ is disabled (outbox relay off) — cannot replay: $messageId",
            )
        }

        override fun discard(messageId: String, reason: String): DlqMessageDetail {
            throw IllegalDlqOperationException(
                "DLQ is disabled (outbox relay off) — cannot discard: $messageId",
            )
        }

        override fun aggregateStats(
            filter: DlqMessageFilter,
            from: Instant,
            to: Instant,
            bucket: Duration,
        ): List<StatsRow> = emptyList()
    }
}
