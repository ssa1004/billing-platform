package com.example.billing.bootstrap.config

import io.github.resilience4j.bulkhead.ThreadPoolBulkhead
import io.github.resilience4j.bulkhead.ThreadPoolBulkheadConfig
import io.github.resilience4j.bulkhead.ThreadPoolBulkheadRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.MDC
import java.time.Duration
import java.util.Optional
import java.util.concurrent.atomic.AtomicReference

/**
 * [MdcContextPropagator] 단위 테스트.
 *
 * ContextPropagator 명세 검증:
 * - retrieve — caller MDC snapshot.
 * - copy — worker thread 진입 시 MDC 복원.
 * - clear — worker 작업 끝 후 MDC 정리 (thread reuse 안전).
 * - 비어있는 MDC 는 [Optional.empty] 로 처리 (worker 의 기존 MDC 를 빈 값으로 덮어쓰지 않음).
 *
 * 실제 ThreadPoolBulkhead 와 wiring 한 통합 검증도 포함 — yaml 설정 없이도 customizer 가
 * propagator 를 제대로 연결하는지 확인.
 */
class MdcContextPropagatorTest {

    private lateinit var propagator: MdcContextPropagator

    @BeforeEach
    fun setUp() {
        propagator = MdcContextPropagator()
        MDC.clear()
    }

    @AfterEach
    fun tearDown() {
        MDC.clear()
    }

    @Test
    fun retrieve_emptyMdc_returnsEmpty() {
        val snapshot = propagator.retrieve().get()
        assertThat(snapshot).isEmpty
    }

    @Test
    fun retrieve_populatedMdc_returnsSnapshot() {
        MDC.put("traceId", "abc-123")
        MDC.put("requestId", "req-456")

        val snapshot = propagator.retrieve().get()

        assertThat(snapshot).isPresent
        assertThat(snapshot.get())
            .containsEntry("traceId", "abc-123")
            .containsEntry("requestId", "req-456")
    }

    @Test
    fun retrieve_returnsImmutableSnapshot_callerMutationDoesNotAffect() {
        MDC.put("traceId", "original")
        val snapshot = propagator.retrieve().get()

        // caller 가 이후 MDC 를 변경해도 snapshot 은 고정 — Map.copyOf 가 보장.
        MDC.put("traceId", "mutated")
        MDC.put("newKey", "added-after")

        assertThat(snapshot).isPresent
        assertThat(snapshot.get()).containsEntry("traceId", "original")
        assertThat(snapshot.get()).doesNotContainKey("newKey")
    }

    @Test
    fun copy_emptyOptional_doesNotTouchMdc() {
        // worker thread 의 기존 MDC (예: 평소 비어있음) 가 빈 snapshot 으로 오염되지 않아야 함.
        MDC.put("workerExisting", "preserved")
        propagator.copy().accept(Optional.empty())

        // empty snapshot 은 no-op — 기존 MDC 그대로.
        assertThat(MDC.get("workerExisting")).isEqualTo("preserved")
    }

    @Test
    fun copy_populatedSnapshot_setsMdc() {
        val snapshot = mapOf("traceId" to "tx-1", "customerId" to "alice")

        propagator.copy().accept(Optional.of(snapshot))

        assertThat(MDC.get("traceId")).isEqualTo("tx-1")
        assertThat(MDC.get("customerId")).isEqualTo("alice")
    }

    @Test
    fun clear_removesAllMdc_threadReuseSafe() {
        // worker 가 작업 중에 set 한 MDC.
        MDC.put("traceId", "tx-1")
        MDC.put("customerId", "alice")

        propagator.clear().accept(Optional.of(mapOf("traceId" to "tx-1")))

        // 다음 작업 전에 MDC 가 깨끗해야 thread reuse 시 이전 작업의 traceId 가 새 작업 로그에
        // 노이즈로 따라가지 않음.
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty()
    }

    /**
     * 실제 ThreadPoolBulkhead 와 통합 — caller thread 의 MDC 가 worker thread 의 로그까지 따라가는
     * 핵심 동작 검증.
     */
    @Test
    fun integration_mdcPropagatesToBulkheadWorker() {
        val config = ThreadPoolBulkheadConfig.custom()
            .maxThreadPoolSize(2)
            .coreThreadPoolSize(1)
            .queueCapacity(2)
            .keepAliveDuration(Duration.ofMillis(20))
            .contextPropagator(propagator) // <-- 핵심 wiring
            .build()
        val registry = ThreadPoolBulkheadRegistry.of(config)
        val bulkhead = registry.bulkhead("test-pg")

        // caller thread 의 MDC 를 박아둔 뒤 worker 가 그 값을 보는지.
        MDC.put("traceId", "caller-trace-xyz")
        MDC.put("requestId", "req-789")

        val traceInWorker = AtomicReference<String>()
        val requestInWorker = AtomicReference<String>()
        val threadInWorker = AtomicReference<String>()

        try {
            val future = bulkhead.executeRunnable {
                traceInWorker.set(MDC.get("traceId"))
                requestInWorker.set(MDC.get("requestId"))
                threadInWorker.set(Thread.currentThread().name)
            }.toCompletableFuture()
            future.get()
        } finally {
            closeQuietly(bulkhead)
        }

        // worker 가 별도 thread 였음을 확인 (caller 와 다른 thread 에서 실행됐다는 사실).
        assertThat(threadInWorker.get()).isNotEqualTo(Thread.currentThread().name)

        // 핵심: worker thread 안에서도 caller 의 traceId / requestId 가 보였다.
        assertThat(traceInWorker.get()).isEqualTo("caller-trace-xyz")
        assertThat(requestInWorker.get()).isEqualTo("req-789")
    }

    /**
     * Thread reuse 시 이전 작업의 MDC 가 다음 작업으로 새지 않아야 함.
     */
    @Test
    fun integration_workerMdcCleanedBetweenJobs() {
        val config = ThreadPoolBulkheadConfig.custom()
            .maxThreadPoolSize(1)        // 강제로 worker 1개 → 두 작업이 같은 thread 로 reuse
            .coreThreadPoolSize(1)
            .queueCapacity(2)
            .keepAliveDuration(Duration.ofSeconds(30)) // keep alive 길게 → 같은 worker 재사용 보장
            .contextPropagator(propagator)
            .build()
        val registry = ThreadPoolBulkheadRegistry.of(config)
        val bulkhead = registry.bulkhead("reuse-test")

        // 작업 1: traceId 박힘.
        MDC.put("traceId", "job-1")
        val threadName1 = AtomicReference<String>()
        val traceJob1 = AtomicReference<String>()
        try {
            bulkhead.executeRunnable {
                threadName1.set(Thread.currentThread().name)
                traceJob1.set(MDC.get("traceId"))
            }.toCompletableFuture().get()
            MDC.clear()

            // 작업 2: caller MDC 비어있음 (Optional.empty).
            //          worker thread 가 작업 1 의 traceId 를 그대로 들고 있으면 새는 것 — clear 가 막아야 함.
            val threadName2 = AtomicReference<String>()
            val mdcInJob2 = AtomicReference<Map<String, String>>()
            bulkhead.executeRunnable {
                threadName2.set(Thread.currentThread().name)
                val ctx: Map<String, String>? = MDC.getCopyOfContextMap()
                mdcInJob2.set(if (ctx == null) emptyMap() else HashMap(ctx))
            }.toCompletableFuture().get()

            assertThat(traceJob1.get()).isEqualTo("job-1")
            // 핵심: 작업 2 의 worker MDC 에 작업 1 의 traceId 가 없어야 함.
            assertThat(mdcInJob2.get()).doesNotContainKey("traceId")
            // 같은 worker thread 에서 재사용된 게 맞다 (keepAlive + maxPool=1).
            assertThat(threadName2.get()).isEqualTo(threadName1.get())
        } finally {
            closeQuietly(bulkhead)
        }
    }

    companion object {
        /** AutoCloseable.close() 가 throws Exception — test cleanup 에서는 best-effort 로 무시. */
        private fun closeQuietly(bulkhead: ThreadPoolBulkhead) {
            try {
                bulkhead.close()
            } catch (_: Exception) {
                /* test-only — best effort */
            }
        }
    }
}
