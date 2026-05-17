package com.example.billing.application.service;

import com.example.billing.application.dto.DlqBulkJob;
import com.example.billing.application.dto.DlqBulkResult;
import com.example.billing.application.dto.DlqMessageDetail;
import com.example.billing.application.dto.DlqMessageFilter;
import com.example.billing.application.dto.DlqMessageView;
import com.example.billing.application.dto.DlqSource;
import com.example.billing.application.exception.IllegalDlqOperationException;
import com.example.billing.application.port.in.AuditLogger;
import com.example.billing.application.port.out.DlqBulkJobRepository;
import com.example.billing.application.port.out.DlqMessageStore;
import com.example.billing.domain.audit.AuditAction;
import com.example.billing.domain.audit.AuditActor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link DlqBulkAdminService} 단위 테스트.
 *
 * <p>핵심 invariant:
 * <ul>
 *   <li>{@code confirm=false} (default) → dry-run 강제. store 의 replay/discard 호출 X. audit
 *       는 DRYRUN 1건.</li>
 *   <li>{@code confirm=true} → 비동기 worker 가 실행. START + FINISH audit 1쌍. job repository
 *       에 결과 영속.</li>
 *   <li>한 항목 실패 (RuntimeException) → 다른 항목 진행. failure 카운트. firstError 보존.</li>
 *   <li>한 항목이 IllegalDlqOperationException (이미 처리됨) → skip 으로 처리, failure 카운트 X.</li>
 *   <li>bulk-discard 의 reason blank → IllegalArgumentException.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DlqBulkAdminServiceTest {

    /** 단위 테스트용 no-op 트랜잭션 매니저 — TransactionTemplate 콜백을 그대로 실행. */
    private static final PlatformTransactionManager NO_OP_TX_MANAGER = new PlatformTransactionManager() {
        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) throws TransactionException {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) throws TransactionException {
        }

        @Override
        public void rollback(TransactionStatus status) throws TransactionException {
        }
    };

    /** 동기 executor — 테스트가 결과를 즉시 검증할 수 있게 같은 thread 에서 실행. */
    private static final Executor SYNC_EXECUTOR = Runnable::run;

    @Mock DlqMessageStore store;
    @Mock AuditLogger auditLogger;
    @Mock DlqBulkJobRepository jobRepository;

    DlqBulkAdminService service;

    private AuditActor operator;

    @BeforeEach
    void setUp() {
        operator = AuditActor.operator("op-bob", "10.0.0.2", "Curl/8");
        service = new DlqBulkAdminService(
                store, auditLogger, jobRepository, SYNC_EXECUTOR, NO_OP_TX_MANAGER);
    }

    @Test
    void bulkReplay_dryRun_default_doesNotCallStoreWrites() {
        when(store.count(any())).thenReturn(3L);
        when(store.search(any(), eq(null), eq(DlqMessageStore.SAMPLE_SIZE))).thenReturn(List.of(
                view("billing.payment.captured.DLT:0:1"),
                view("billing.payment.captured.DLT:0:2"),
                view("billing.payment.captured.DLT:0:3")));

        DlqBulkResult result = service.bulkReplay(
                DlqMessageFilter.EMPTY, false, "test dry-run", operator);

        assertThat(result.mode()).isEqualTo(DlqBulkResult.Mode.DRY_RUN);
        assertThat(result.estimatedCount()).isEqualTo(3L);
        assertThat(result.sampleMessageIds()).hasSize(3);
        assertThat(result.jobId()).isNull();
        verify(store, never()).replay(anyString());
        verify(auditLogger).log(eq(operator), eq(AuditAction.DLQ_BULK_REPLAY_DRYRUN),
                eq(DlqAdminService.TARGET_TYPE), eq("bulk-replay"),
                eq(null), anyString(), eq("test dry-run"));
    }

    @Test
    void bulkReplay_confirm_true_invokesStoreReplayPerMessage_andEmitsStartAndFinishAudits() {
        DlqMessageView m1 = view("billing.payment.captured.DLT:0:1");
        DlqMessageView m2 = view("billing.payment.captured.DLT:0:2");
        when(store.count(any())).thenReturn(2L);
        when(store.search(any(), eq(null), eq(DlqMessageStore.SAMPLE_SIZE)))
                .thenReturn(List.of(m1, m2));
        // worker 의 첫 batch — 같은 List 를 두 번째 호출 시에는 빈 list 반환 (cursor 진행 → end).
        when(store.search(any(), eq(null), eq(DlqBulkAdminService.BULK_BATCH_SIZE)))
                .thenReturn(List.of(m1, m2));
        when(store.search(any(), eq(m2.messageId()), eq(DlqBulkAdminService.BULK_BATCH_SIZE)))
                .thenReturn(List.of());

        when(store.replay(m1.messageId())).thenReturn(detail(m1.messageId()));
        when(store.replay(m2.messageId())).thenReturn(detail(m2.messageId()));

        DlqBulkResult result = service.bulkReplay(
                DlqMessageFilter.EMPTY, true, "vendor recovery", operator);

        assertThat(result.mode()).isEqualTo(DlqBulkResult.Mode.EXECUTING);
        assertThat(result.jobId()).isNotNull();
        verify(store, times(2)).replay(anyString());

        // START audit (executing 분기 진입 시 1회).
        verify(auditLogger).log(eq(operator), eq(AuditAction.DLQ_BULK_REPLAY_START),
                eq(DlqAdminService.TARGET_TYPE), eq("bulk-replay"),
                eq(null), anyString(), eq("vendor recovery"));

        // FINISH audit (worker 종료 시 1회) — SYNC_EXECUTOR 라 동기 실행됨.
        ArgumentCaptor<String> finishAfter = ArgumentCaptor.forClass(String.class);
        verify(auditLogger).log(eq(operator), eq(AuditAction.DLQ_BULK_REPLAY_FINISH),
                eq(DlqAdminService.TARGET_TYPE), eq("bulk-replay"),
                eq(null), finishAfter.capture(), eq("vendor recovery"));
        assertThat(finishAfter.getValue()).contains("\"state\":\"SUCCEEDED\"");
        // AuditPayloads.put(key, Any?) 가 toString 결과를 quoted string 으로 직렬화 — 숫자도 따옴표 포함.
        assertThat(finishAfter.getValue()).contains("\"success\":\"2\"");
    }

    @Test
    void bulkReplay_partialFailure_keepsGoing_andRecordsFirstError() {
        DlqMessageView m1 = view("billing.payment.captured.DLT:0:1");
        DlqMessageView m2 = view("billing.payment.captured.DLT:0:2");
        when(store.count(any())).thenReturn(2L);
        when(store.search(any(), eq(null), eq(DlqMessageStore.SAMPLE_SIZE)))
                .thenReturn(List.of(m1, m2));
        when(store.search(any(), eq(null), eq(DlqBulkAdminService.BULK_BATCH_SIZE)))
                .thenReturn(List.of(m1, m2));
        when(store.search(any(), eq(m2.messageId()), eq(DlqBulkAdminService.BULK_BATCH_SIZE)))
                .thenReturn(List.of());
        when(store.replay(m1.messageId())).thenThrow(new RuntimeException("PG 5xx"));
        when(store.replay(m2.messageId())).thenReturn(detail(m2.messageId()));

        service.bulkReplay(DlqMessageFilter.EMPTY, true, "partial test", operator);

        verify(store).replay(m1.messageId());
        verify(store).replay(m2.messageId());
        ArgumentCaptor<String> finishAfter = ArgumentCaptor.forClass(String.class);
        verify(auditLogger).log(eq(operator), eq(AuditAction.DLQ_BULK_REPLAY_FINISH),
                eq(DlqAdminService.TARGET_TYPE), eq("bulk-replay"),
                eq(null), finishAfter.capture(), eq("partial test"));
        assertThat(finishAfter.getValue()).contains("\"state\":\"PARTIAL_FAILURE\"");
        assertThat(finishAfter.getValue()).contains("\"failure\":\"1\"");
        assertThat(finishAfter.getValue()).contains("\"success\":\"1\"");
        assertThat(finishAfter.getValue()).contains("RuntimeException: PG 5xx");
    }

    @Test
    void bulkReplay_illegalDlqOperation_isSkip_notFailure() {
        DlqMessageView m1 = view("billing.payment.captured.DLT:0:1");
        when(store.count(any())).thenReturn(1L);
        when(store.search(any(), eq(null), eq(DlqMessageStore.SAMPLE_SIZE))).thenReturn(List.of(m1));
        when(store.search(any(), eq(null), eq(DlqBulkAdminService.BULK_BATCH_SIZE)))
                .thenReturn(List.of(m1));
        when(store.search(any(), eq(m1.messageId()), eq(DlqBulkAdminService.BULK_BATCH_SIZE)))
                .thenReturn(List.of());
        when(store.replay(m1.messageId()))
                .thenThrow(new IllegalDlqOperationException("already processed"));

        service.bulkReplay(DlqMessageFilter.EMPTY, true, "skip-test", operator);

        ArgumentCaptor<String> finishAfter = ArgumentCaptor.forClass(String.class);
        verify(auditLogger).log(eq(operator), eq(AuditAction.DLQ_BULK_REPLAY_FINISH),
                eq(DlqAdminService.TARGET_TYPE), eq("bulk-replay"),
                eq(null), finishAfter.capture(), eq("skip-test"));
        // skip 은 success / failure 둘 다 0.
        assertThat(finishAfter.getValue()).contains("\"success\":\"0\"");
        assertThat(finishAfter.getValue()).contains("\"failure\":\"0\"");
        // 한 batch processed → SUCCEEDED (failure 0).
        assertThat(finishAfter.getValue()).contains("\"state\":\"SUCCEEDED\"");
    }

    @Test
    void bulkDiscard_blankReason_throws() {
        assertThatThrownBy(() -> service.bulkDiscard(
                DlqMessageFilter.EMPTY, false, "  ", operator))
                .isInstanceOf(IllegalArgumentException.class);
        verify(store, never()).count(any());
    }

    @Test
    void bulkDiscard_dryRun_emitsDiscardDryrunAudit() {
        when(store.count(any())).thenReturn(0L);

        DlqBulkResult result = service.bulkDiscard(
                DlqMessageFilter.EMPTY, false, "spam batch", operator);

        assertThat(result.mode()).isEqualTo(DlqBulkResult.Mode.DRY_RUN);
        verify(auditLogger).log(eq(operator), eq(AuditAction.DLQ_BULK_DISCARD_DRYRUN),
                eq(DlqAdminService.TARGET_TYPE), eq("bulk-discard"),
                eq(null), anyString(), eq("spam batch"));
    }

    @Test
    void getBulkJob_delegates() {
        UUID jobId = UUID.randomUUID();
        DlqBulkJob job = new DlqBulkJob(jobId, DlqBulkJob.Operation.REPLAY,
                DlqBulkJob.State.RUNNING, 10, 0, 0, 0, Instant.now(), null, null);
        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));

        Optional<DlqBulkJob> found = service.getBulkJob(jobId);

        assertThat(found).isPresent().get().isEqualTo(job);
    }

    @Test
    void unknownConsumerGroup_shortCircuits_dryRunZero() {
        DlqMessageFilter filter = new DlqMessageFilter(
                null, null, "unknown-service-group", null, null, null);

        DlqBulkResult result = service.bulkReplay(filter, true, "should-noop", operator);

        assertThat(result.mode()).isEqualTo(DlqBulkResult.Mode.DRY_RUN);
        assertThat(result.estimatedCount()).isZero();
        verify(store, never()).count(any());
        verify(store, never()).replay(anyString());
    }

    // ── helpers ──

    private static DlqMessageView view(String messageId) {
        return new DlqMessageView(
                messageId, DlqSource.PAYMENT.name(),
                "billing.payment.captured.DLT", "billing.payment.captured",
                0, 1L, "key", "PgException", "PG 5xx",
                Instant.parse("2026-05-15T10:00:00Z"), 100);
    }

    private static DlqMessageDetail detail(String messageId) {
        Map<String, String> headers = new LinkedHashMap<>();
        return new DlqMessageDetail(
                messageId, DlqSource.PAYMENT.name(),
                "billing.payment.captured.DLT", "billing.payment.captured",
                0, 1L, "key", "{}", 2, headers,
                "PgException", "PgException: PG 5xx", "stack",
                "billing-payment-consumer",
                Instant.parse("2026-05-15T10:00:00Z"),
                Instant.parse("2026-05-15T10:00:00Z"),
                0, null, null);
    }
}
