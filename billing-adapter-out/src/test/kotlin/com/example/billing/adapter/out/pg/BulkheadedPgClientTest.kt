package com.example.billing.adapter.out.pg

import com.example.billing.application.port.out.PgClient
import com.example.billing.application.port.out.PgClient.AuthorizeRequest
import com.example.billing.application.port.out.PgClient.AuthorizeResult
import com.example.billing.application.port.out.PgClient.LookupResult
import com.example.billing.application.port.out.PgClient.LookupStatus
import com.example.billing.application.port.out.PgClient.RefundRequest
import com.example.billing.application.port.out.PgClient.RefundResult
import com.example.billing.domain.payment.PaymentMethod
import com.example.billing.domain.shared.Money
import io.github.resilience4j.bulkhead.ThreadPoolBulkheadConfig
import io.github.resilience4j.bulkhead.ThreadPoolBulkheadRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Duration
import java.util.Currency
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * BulkheadedPgClient 단위 테스트.
 *
 * 검증:
 *  - 정상 호출은 delegate 결과 그대로 반환.
 *  - bulkhead 가 가득 차면 fallback 으로 즉시 응답 (BULKHEAD_FULL).
 *  - 작은 풀 사이즈로 격리 효과를 verify — 동시 호출이 풀 + queue 합을 초과하면 BulkheadFullException.
 */
class BulkheadedPgClientTest {

    private lateinit var registry: ThreadPoolBulkheadRegistry
    private lateinit var delegate: RecordingPgClient

    @BeforeEach
    fun setUp() {
        val config = ThreadPoolBulkheadConfig.custom()
            .maxThreadPoolSize(2)
            .coreThreadPoolSize(2)
            .queueCapacity(2)               // pool 2 + queue 2 = 동시 4건까지만
            .keepAliveDuration(Duration.ofMillis(20))
            .build()
        registry = ThreadPoolBulkheadRegistry.of(config)
        delegate = RecordingPgClient()
    }

    @AfterEach
    fun tearDown() {
        registry.allBulkheads.forEach { b ->
            try {
                b.close()
            } catch (ignored: Exception) {
                /* test-only — best effort */
            }
        }
    }

    @Test
    fun authorize_underCapacity_returnsDelegateResult() {
        val client = BulkheadedPgClient(delegate, registry)

        val req = AuthorizeRequest("k-1", money(1000), PaymentMethod.CARD, "order-1")
        val result = client.authorize(req)

        assertThat(result.approved).isTrue()
        assertThat(result.pgTransactionId).startsWith("pg-")
    }

    @Test
    fun refund_underCapacity_returnsDelegateResult() {
        val client = BulkheadedPgClient(delegate, registry)

        val req = RefundRequest("pg-tx-1", money(500), "test")
        val result = client.refund(req)

        assertThat(result.approved).isTrue()
    }

    @Test
    fun authorize_bulkheadFull_returnsFallback() {
        // 풀 + 큐 합이 4 — 5번째 호출은 BulkheadFullException.
        delegate.blockUntilLatch()   // delegate 가 latch 풀릴 때까지 무한 대기 → worker 점유
        val client = BulkheadedPgClient(delegate, registry)

        val fallbackCount = AtomicInteger()
        val okCount = AtomicInteger()
        val threads = Array(6) { _ ->
            Thread {
                val req = AuthorizeRequest("k-" + Math.random(), money(100), PaymentMethod.CARD, "order-x")
                val result = client.authorize(req)
                if (result.errorCode == "BULKHEAD_FULL") {
                    fallbackCount.incrementAndGet()
                } else {
                    okCount.incrementAndGet()
                }
            }
        }
        threads.forEach { it.start() }

        // 잠깐 기다려 6 호출 모두 시도된 상태가 되도록 + 5,6 번째는 즉시 BulkheadFullException.
        Thread.sleep(300)
        delegate.releaseLatch()
        threads.forEach { it.join(2000) }

        // 6 호출 중 적어도 일부는 BULKHEAD_FULL fallback 으로 떨어져야 함.
        assertThat(fallbackCount.get()).isGreaterThan(0)
        assertThat(fallbackCount.get() + okCount.get()).isEqualTo(6)
    }

    @Test
    fun lookup_bulkheadFull_returnsInProgress() {
        delegate.blockUntilLatch()
        val client = BulkheadedPgClient(delegate, registry)

        val inProgressCount = AtomicInteger()
        val threads = Array(6) { _ ->
            Thread {
                val result = client.lookup("k-" + Math.random())
                if (result.status == LookupStatus.IN_PROGRESS) {
                    inProgressCount.incrementAndGet()
                }
            }
        }
        threads.forEach { it.start() }

        Thread.sleep(300)
        delegate.releaseLatch()
        threads.forEach { it.join(2000) }

        // bulkhead full 이면 lookup 은 IN_PROGRESS 로 fallback (reconciler 가 다음 사이클에 retry).
        assertThat(inProgressCount.get()).isGreaterThan(0)
    }

    private fun money(amount: Long): Money =
        Money.of(BigDecimal.valueOf(amount), Currency.getInstance("KRW"))

    /**
     * 호출 시 latch 가 release 될 때까지 무한 대기 — bulkhead 의 worker 가 점유된 상태를 시뮬레이션.
     */
    private class RecordingPgClient : PgClient {
        private var latch: CountDownLatch? = null
        private val callCount = AtomicInteger()

        fun blockUntilLatch() {
            latch = CountDownLatch(1)
        }

        fun releaseLatch() {
            latch?.countDown()
        }

        private fun waitIfNeeded() {
            try {
                latch?.await(5, TimeUnit.SECONDS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }

        override fun authorize(request: AuthorizeRequest): AuthorizeResult {
            waitIfNeeded()
            return AuthorizeResult.approved("pg-" + callCount.incrementAndGet())
        }

        override fun refund(request: RefundRequest): RefundResult {
            waitIfNeeded()
            return RefundResult.approved("rf-" + callCount.incrementAndGet())
        }

        override fun lookup(idempotencyKey: String): LookupResult {
            waitIfNeeded()
            return LookupResult.approved("lk-" + callCount.incrementAndGet())
        }
    }
}
