package com.example.billing.bootstrap.config;

import io.github.resilience4j.bulkhead.ThreadPoolBulkhead;
import io.github.resilience4j.bulkhead.ThreadPoolBulkheadConfig;
import io.github.resilience4j.bulkhead.ThreadPoolBulkheadRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link MdcContextPropagator} 단위 테스트.
 *
 * <p>ContextPropagator 명세 검증:
 * <ul>
 *   <li>retrieve — caller MDC snapshot.</li>
 *   <li>copy — worker thread 진입 시 MDC 복원.</li>
 *   <li>clear — worker 작업 끝 후 MDC 정리 (thread reuse 안전).</li>
 *   <li>비어있는 MDC 는 {@link Optional#empty()} 로 처리 (worker 의 기존 MDC 를 빈 값으로 덮어쓰지 않음).</li>
 * </ul>
 *
 * <p>실제 ThreadPoolBulkhead 와 wiring 한 통합 검증도 포함 — yaml 설정 없이도 customizer 가
 * propagator 를 제대로 연결하는지 확인.</p>
 */
class MdcContextPropagatorTest {

    private MdcContextPropagator propagator;

    @BeforeEach
    void setUp() {
        propagator = new MdcContextPropagator();
        MDC.clear();
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    void retrieve_emptyMdc_returnsEmpty() {
        Optional<Map<String, String>> snapshot = propagator.retrieve().get();
        assertThat(snapshot).isEmpty();
    }

    @Test
    void retrieve_populatedMdc_returnsSnapshot() {
        MDC.put("traceId", "abc-123");
        MDC.put("requestId", "req-456");

        Optional<Map<String, String>> snapshot = propagator.retrieve().get();

        assertThat(snapshot).isPresent();
        assertThat(snapshot.get())
                .containsEntry("traceId", "abc-123")
                .containsEntry("requestId", "req-456");
    }

    @Test
    void retrieve_returnsImmutableSnapshot_callerMutationDoesNotAffect() {
        MDC.put("traceId", "original");
        Optional<Map<String, String>> snapshot = propagator.retrieve().get();

        // caller 가 이후 MDC 를 변경해도 snapshot 은 고정 — Map.copyOf 가 보장.
        MDC.put("traceId", "mutated");
        MDC.put("newKey", "added-after");

        assertThat(snapshot).isPresent();
        assertThat(snapshot.get()).containsEntry("traceId", "original");
        assertThat(snapshot.get()).doesNotContainKey("newKey");
    }

    @Test
    void copy_emptyOptional_doesNotTouchMdc() {
        // worker thread 의 기존 MDC (예: 평소 비어있음) 가 빈 snapshot 으로 *오염되지 않아야* 함.
        MDC.put("workerExisting", "preserved");
        propagator.copy().accept(Optional.empty());

        // empty snapshot 은 no-op — 기존 MDC 그대로.
        assertThat(MDC.get("workerExisting")).isEqualTo("preserved");
    }

    @Test
    void copy_populatedSnapshot_setsMdc() {
        Map<String, String> snapshot = Map.of("traceId", "tx-1", "customerId", "alice");

        propagator.copy().accept(Optional.of(snapshot));

        assertThat(MDC.get("traceId")).isEqualTo("tx-1");
        assertThat(MDC.get("customerId")).isEqualTo("alice");
    }

    @Test
    void clear_removesAllMdc_threadReuseSafe() {
        // worker 가 작업 중에 set 한 MDC.
        MDC.put("traceId", "tx-1");
        MDC.put("customerId", "alice");

        propagator.clear().accept(Optional.of(Map.of("traceId", "tx-1")));

        // 다음 작업 전에 MDC 가 깨끗해야 thread reuse 시 이전 작업의 traceId 가 새 작업 로그에
        // 노이즈로 따라가지 않음.
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    /**
     * 실제 ThreadPoolBulkhead 와 통합 — caller thread 의 MDC 가 worker thread 의 로그까지 따라가는
     * 핵심 동작 검증.
     */
    @Test
    void integration_mdcPropagatesToBulkheadWorker() throws ExecutionException, InterruptedException {
        ThreadPoolBulkheadConfig config = ThreadPoolBulkheadConfig.custom()
                .maxThreadPoolSize(2)
                .coreThreadPoolSize(1)
                .queueCapacity(2)
                .keepAliveDuration(Duration.ofMillis(20))
                .contextPropagator(propagator)        // <-- 핵심 wiring
                .build();
        ThreadPoolBulkheadRegistry registry = ThreadPoolBulkheadRegistry.of(config);
        ThreadPoolBulkhead bulkhead = registry.bulkhead("test-pg");

        // caller thread 의 MDC 를 박아둔 뒤 worker 가 그 값을 보는지.
        MDC.put("traceId", "caller-trace-xyz");
        MDC.put("requestId", "req-789");

        AtomicReference<String> traceInWorker = new AtomicReference<>();
        AtomicReference<String> requestInWorker = new AtomicReference<>();
        AtomicReference<String> threadInWorker = new AtomicReference<>();

        try {
            CompletableFuture<Void> future = bulkhead.executeRunnable(() -> {
                traceInWorker.set(MDC.get("traceId"));
                requestInWorker.set(MDC.get("requestId"));
                threadInWorker.set(Thread.currentThread().getName());
            }).toCompletableFuture();
            future.get();
        } finally {
            closeQuietly(bulkhead);
        }

        // worker 가 별도 thread 였음을 확인 (caller 와 다른 thread 에서 실행됐다는 사실).
        assertThat(threadInWorker.get()).isNotEqualTo(Thread.currentThread().getName());

        // 핵심: worker thread 안에서도 caller 의 traceId / requestId 가 보였다.
        assertThat(traceInWorker.get()).isEqualTo("caller-trace-xyz");
        assertThat(requestInWorker.get()).isEqualTo("req-789");
    }

    /**
     * Thread reuse 시 이전 작업의 MDC 가 다음 작업으로 *새지 않아야* 함.
     */
    @Test
    void integration_workerMdcCleanedBetweenJobs() throws ExecutionException, InterruptedException {
        ThreadPoolBulkheadConfig config = ThreadPoolBulkheadConfig.custom()
                .maxThreadPoolSize(1)        // 강제로 worker 1개 → 두 작업이 같은 thread 로 reuse
                .coreThreadPoolSize(1)
                .queueCapacity(2)
                .keepAliveDuration(Duration.ofSeconds(30))   // keep alive 길게 → 같은 worker 재사용 보장
                .contextPropagator(propagator)
                .build();
        ThreadPoolBulkheadRegistry registry = ThreadPoolBulkheadRegistry.of(config);
        ThreadPoolBulkhead bulkhead = registry.bulkhead("reuse-test");

        // 작업 1: traceId 박힘.
        MDC.put("traceId", "job-1");
        AtomicReference<String> threadName1 = new AtomicReference<>();
        AtomicReference<String> traceJob1 = new AtomicReference<>();
        try {
            bulkhead.executeRunnable(() -> {
                threadName1.set(Thread.currentThread().getName());
                traceJob1.set(MDC.get("traceId"));
            }).toCompletableFuture().get();
            MDC.clear();

            // 작업 2: caller MDC 비어있음 (Optional.empty).
            //          worker thread 가 *작업 1 의 traceId* 를 그대로 들고 있으면 새는 것 — clear 가 막아야 함.
            AtomicReference<String> threadName2 = new AtomicReference<>();
            AtomicReference<Map<String, String>> mdcInJob2 = new AtomicReference<>();
            bulkhead.executeRunnable(() -> {
                threadName2.set(Thread.currentThread().getName());
                Map<String, String> ctx = MDC.getCopyOfContextMap();
                mdcInJob2.set(ctx == null ? new HashMap<>() : new HashMap<>(ctx));
            }).toCompletableFuture().get();

            assertThat(traceJob1.get()).isEqualTo("job-1");
            // 핵심: 작업 2 의 worker MDC 에 작업 1 의 traceId 가 *없어야* 함.
            assertThat(mdcInJob2.get()).doesNotContainKey("traceId");
            // 같은 worker thread 에서 재사용된 게 맞다 (keepAlive + maxPool=1).
            assertThat(threadName2.get()).isEqualTo(threadName1.get());
        } finally {
            closeQuietly(bulkhead);
        }
    }

    /** AutoCloseable.close() 가 throws Exception — test cleanup 에서는 best-effort 로 무시. */
    private static void closeQuietly(ThreadPoolBulkhead bulkhead) {
        try {
            bulkhead.close();
        } catch (Exception ignored) {
            /* test-only — best effort */
        }
    }
}
