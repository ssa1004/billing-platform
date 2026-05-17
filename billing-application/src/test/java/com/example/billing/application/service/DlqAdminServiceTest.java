package com.example.billing.application.service;

import com.example.billing.application.dto.DlqListPage;
import com.example.billing.application.dto.DlqMessageDetail;
import com.example.billing.application.dto.DlqMessageFilter;
import com.example.billing.application.dto.DlqMessageView;
import com.example.billing.application.dto.DlqSource;
import com.example.billing.application.dto.DlqStats;
import com.example.billing.application.exception.IllegalDlqOperationException;
import com.example.billing.application.port.in.AuditLogger;
import com.example.billing.application.port.out.DlqMessageStore;
import com.example.billing.domain.audit.AuditAction;
import com.example.billing.domain.audit.AuditActor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link DlqAdminService} 단위 테스트 — search / detail / replay / discard / stats 와 audit 발행
 * 검증.
 *
 * <p>핵심 invariant: 모든 write endpoint 가 audit log 를 정확한 action / targetType / targetId /
 * customerId 로 발행. 단건 replay / discard 의 멱등 가드 (어댑터가 던지는
 * IllegalDlqOperationException) 가 그대로 controller 로 전파됨.
 */
@ExtendWith(MockitoExtension.class)
class DlqAdminServiceTest {

    private static final Instant NOW = Instant.parse("2026-05-15T10:00:00Z");

    @Mock DlqMessageStore store;
    @Mock AuditLogger auditLogger;

    @InjectMocks DlqAdminService service;

    private AuditActor operator;

    @BeforeEach
    void setUp() {
        operator = AuditActor.operator("op-alice", "10.0.0.1", "Chrome/123");
    }

    @Test
    void search_returnsItems_andEmitsCursor_whenPageFull() {
        DlqMessageFilter filter = DlqMessageFilter.EMPTY;
        List<DlqMessageView> rows = List.of(view("billing.payment.captured.DLT:0:1"),
                view("billing.payment.captured.DLT:0:2"));
        when(store.search(filter, null, 2)).thenReturn(rows);

        DlqListPage page = service.search(filter, null, 2);

        assertThat(page.items()).hasSize(2);
        assertThat(page.nextCursor()).isEqualTo("billing.payment.captured.DLT:0:2");
        assertThat(page.size()).isEqualTo(2);
    }

    @Test
    void search_returnsNullCursor_whenPageShorterThanSize() {
        when(store.search(any(), eq(null), eq(50)))
                .thenReturn(List.of(view("billing.payment.captured.DLT:0:1")));

        DlqListPage page = service.search(DlqMessageFilter.EMPTY, null, 50);

        assertThat(page.nextCursor()).isNull();
        assertThat(page.size()).isEqualTo(50);
    }

    @Test
    void search_unknownConsumerGroup_shortCircuitsToEmptyPage() {
        DlqMessageFilter filter = new DlqMessageFilter(
                null, null, "unknown-service-group", null, null, null);

        DlqListPage page = service.search(filter, null, 50);

        assertThat(page.items()).isEmpty();
        verifyNoInteractions(store);
    }

    @Test
    void search_clampsSizeToMaxPageSize() {
        when(store.search(any(), eq(null), eq(DlqAdminService.MAX_PAGE_SIZE)))
                .thenReturn(List.of());

        DlqListPage page = service.search(DlqMessageFilter.EMPTY, null, 10_000);

        assertThat(page.size()).isEqualTo(DlqAdminService.MAX_PAGE_SIZE);
    }

    @Test
    void replay_emitsAudit_withCustomerAndIdempotencyKey() {
        String messageId = "billing.payment.captured.DLT:0:1";
        DlqMessageDetail detail = detail(messageId, "billing.payment.captured", "cust-42", "idem-xyz");
        when(store.replay(messageId)).thenReturn(detail);

        DlqMessageView result = service.replay(messageId, operator);

        assertThat(result.messageId()).isEqualTo(messageId);
        ArgumentCaptor<String> afterCaptor = ArgumentCaptor.forClass(String.class);
        verify(auditLogger).log(
                eq(operator),
                eq(AuditAction.DLQ_REPLAY),
                eq(DlqAdminService.TARGET_TYPE),
                eq(messageId),
                anyString(),
                afterCaptor.capture(),
                eq(null));
        String afterJson = afterCaptor.getValue();
        assertThat(afterJson).contains("\"originalTopic\":\"billing.payment.captured\"");
        assertThat(afterJson).contains("\"customerId\":\"cust-42\"");
        assertThat(afterJson).contains("\"idempotencyKey\":\"idem-xyz\"");
    }

    @Test
    void replay_secondCall_propagatesIllegalDlqOperationException() {
        String messageId = "billing.payment.captured.DLT:0:1";
        when(store.replay(messageId))
                .thenThrow(new IllegalDlqOperationException("already processed: " + messageId));

        assertThatThrownBy(() -> service.replay(messageId, operator))
                .isInstanceOf(IllegalDlqOperationException.class);
        // audit 발행되지 않음 — 단건은 첫 호출에서만 audit 가 남고 두 번째는 그대로 거절.
        verify(auditLogger, never()).log(any(), any(), anyString(), anyString(),
                any(), any(), any());
    }

    @Test
    void discard_requiresNonBlankReason() {
        assertThatThrownBy(() -> service.discard("billing.payment.captured.DLT:0:1", "  ", operator))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(store);
        verifyNoInteractions(auditLogger);
    }

    @Test
    void discard_emitsAudit_withReason_andNullAfterJson() {
        String messageId = "billing.refund.failed.DLT:1:42";
        DlqMessageDetail detail = detail(messageId, "billing.refund.failed", "cust-7", null);
        when(store.discard(messageId, "duplicate refund")).thenReturn(detail);

        service.discard(messageId, "duplicate refund", operator);

        verify(auditLogger).log(
                eq(operator),
                eq(AuditAction.DLQ_DISCARD),
                eq(DlqAdminService.TARGET_TYPE),
                eq(messageId),
                anyString(),
                eq(null),
                eq("duplicate refund"));
    }

    @Test
    void stats_aggregatesByBucket_source_errorClass_customer() {
        Instant from = NOW.minus(Duration.ofHours(2));
        Instant to = NOW;
        Duration bucket = Duration.ofHours(1);
        // 두 bucket / 두 source / 두 errorClass / 두 customer.
        List<DlqMessageStore.StatsRow> rows = List.of(
                row(from, "PAYMENT", "PgException", "cust-1", 3L),
                row(from, "REFUND", "PgException", "cust-1", 1L),
                row(to.minus(Duration.ofMinutes(30)), "PAYMENT", "TimeoutException", "cust-2", 5L));
        when(store.aggregateStats(any(), eq(from), eq(to), eq(bucket))).thenReturn(rows);

        DlqStats stats = service.stats(DlqMessageFilter.EMPTY, from, to, bucket);

        assertThat(stats.totalCount()).isEqualTo(9L);
        assertThat(stats.bySource()).extracting(DlqStats.KeyedCount::key)
                .containsExactlyInAnyOrder("PAYMENT", "REFUND");
        assertThat(stats.bySource()).extracting(DlqStats.KeyedCount::count)
                .containsExactlyInAnyOrder(8L, 1L);
        assertThat(stats.byErrorClass()).extracting(DlqStats.KeyedCount::key)
                .containsExactlyInAnyOrder("PgException", "TimeoutException");
        assertThat(stats.byCustomer()).extracting(DlqStats.KeyedCount::key)
                .containsExactlyInAnyOrder("cust-1", "cust-2");
        assertThat(stats.byBucket()).hasSize(2);
        assertThat(stats.byBucket().get(0).bucketStart()).isBefore(stats.byBucket().get(1).bucketStart());
    }

    @Test
    void stats_defaults_from_to_bucket() {
        when(store.aggregateStats(any(), any(), any(), any())).thenReturn(List.of());

        DlqStats stats = service.stats(DlqMessageFilter.EMPTY, null, null, null);

        assertThat(stats.bucketDuration()).isEqualTo(Duration.ofHours(1));
        assertThat(Duration.between(stats.from(), stats.to())).isEqualTo(Duration.ofHours(24));
    }

    @Test
    void stats_rejectsFromAfterTo() {
        assertThatThrownBy(() -> service.stats(
                DlqMessageFilter.EMPTY,
                NOW,
                NOW.minus(Duration.ofHours(1)),
                Duration.ofHours(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void stats_rejectsNegativeBucket() {
        assertThatThrownBy(() -> service.stats(
                DlqMessageFilter.EMPTY, null, null, Duration.ofHours(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── helpers ──

    private static DlqMessageView view(String messageId) {
        return new DlqMessageView(
                messageId, DlqSource.PAYMENT.name(),
                "billing.payment.captured.DLT", "billing.payment.captured",
                0, 1L, "key", "PgException", "PG 5xx", NOW, 100);
    }

    private static DlqMessageDetail detail(
            String messageId, String originalTopic, String customerId, String idempotencyKey) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (idempotencyKey != null) headers.put("Idempotency-Key", idempotencyKey);
        if (customerId != null) headers.put("customer-id", customerId);
        return new DlqMessageDetail(
                messageId,
                inferSourceForTest(originalTopic),
                originalTopic + ".DLT",
                originalTopic,
                0,
                1L,
                "key",
                "{}",
                2,
                headers,
                "PgException",
                "PgException: PG 5xx",
                "stack",
                "billing-payment-consumer",
                NOW,
                NOW,
                3,
                idempotencyKey,
                customerId);
    }

    private static String inferSourceForTest(String originalTopic) {
        if (originalTopic.startsWith("billing.payment")) return DlqSource.PAYMENT.name();
        if (originalTopic.startsWith("billing.refund")) return DlqSource.REFUND.name();
        if (originalTopic.startsWith("billing.settlement")) return DlqSource.SETTLEMENT.name();
        if (originalTopic.startsWith("billing.pg-webhook")) return DlqSource.PG_WEBHOOK.name();
        return DlqSource.OUTBOX.name();
    }

    private static DlqMessageStore.StatsRow row(
            Instant bucketStart, String source, String errorClass, String customerId, long count) {
        return new DlqMessageStore.StatsRow(bucketStart, source, errorClass, customerId, count);
    }
}
