package com.example.billing.adapter.out.pg;

import com.example.billing.application.port.out.PgClient;
import com.example.billing.domain.payment.PaymentMethod;
import com.example.billing.domain.shared.Money;
import io.github.resilience4j.bulkhead.ThreadPoolBulkheadConfig;
import io.github.resilience4j.bulkhead.ThreadPoolBulkheadRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Currency;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BulkheadedPgClient 단위 테스트.
 *
 * 검증:
 *  - 정상 호출은 delegate 결과 그대로 반환.
 *  - bulkhead 가 가득 차면 fallback 으로 즉시 응답 (BULKHEAD_FULL).
 *  - 작은 풀 사이즈로 격리 효과를 verify — 동시 호출이 풀 + queue 합을 초과하면 BulkheadFullException.
 */
class BulkheadedPgClientTest {

    private ThreadPoolBulkheadRegistry registry;
    private RecordingPgClient delegate;

    @BeforeEach
    void setUp() {
        ThreadPoolBulkheadConfig config = ThreadPoolBulkheadConfig.custom()
                .maxThreadPoolSize(2)
                .coreThreadPoolSize(2)
                .queueCapacity(2)               // pool 2 + queue 2 = 동시 4건까지만
                .keepAliveDuration(Duration.ofMillis(20))
                .build();
        registry = ThreadPoolBulkheadRegistry.of(config);
        delegate = new RecordingPgClient();
    }

    @AfterEach
    void tearDown() {
        if (registry != null) {
            registry.getAllBulkheads().forEach(b -> {
                try { b.close(); } catch (Exception ignored) { /* test-only — best effort */ }
            });
        }
    }

    @Test
    void authorize_underCapacity_returnsDelegateResult() {
        BulkheadedPgClient client = new BulkheadedPgClient(delegate, registry);

        var req = new PgClient.AuthorizeRequest("k-1", money(1000), PaymentMethod.CARD, "order-1");
        var result = client.authorize(req);

        assertThat(result.approved()).isTrue();
        assertThat(result.pgTransactionId()).startsWith("pg-");
    }

    @Test
    void refund_underCapacity_returnsDelegateResult() {
        BulkheadedPgClient client = new BulkheadedPgClient(delegate, registry);

        var req = new PgClient.RefundRequest("pg-tx-1", money(500), "test");
        var result = client.refund(req);

        assertThat(result.approved()).isTrue();
    }

    @Test
    void authorize_bulkheadFull_returnsFallback() throws Exception {
        // 풀 + 큐 합이 4 — 5번째 호출은 BulkheadFullException.
        delegate.blockUntilLatch();   // delegate 가 latch 풀릴 때까지 무한 대기 → worker 점유
        BulkheadedPgClient client = new BulkheadedPgClient(delegate, registry);

        AtomicInteger fallbackCount = new AtomicInteger();
        AtomicInteger okCount = new AtomicInteger();
        Thread[] threads = new Thread[6];
        for (int i = 0; i < 6; i++) {
            threads[i] = new Thread(() -> {
                var req = new PgClient.AuthorizeRequest("k-" + Math.random(), money(100),
                        PaymentMethod.CARD, "order-x");
                var result = client.authorize(req);
                if ("BULKHEAD_FULL".equals(result.errorCode())) {
                    fallbackCount.incrementAndGet();
                } else {
                    okCount.incrementAndGet();
                }
            });
            threads[i].start();
        }

        // 잠깐 기다려 6 호출 모두 시도된 상태가 되도록 + 5,6 번째는 즉시 BulkheadFullException.
        Thread.sleep(300);
        delegate.releaseLatch();
        for (Thread t : threads) t.join(2000);

        // 6 호출 중 적어도 일부는 BULKHEAD_FULL fallback 으로 떨어져야 함.
        assertThat(fallbackCount.get()).isGreaterThan(0);
        assertThat(fallbackCount.get() + okCount.get()).isEqualTo(6);
    }

    @Test
    void lookup_bulkheadFull_returnsInProgress() throws Exception {
        delegate.blockUntilLatch();
        BulkheadedPgClient client = new BulkheadedPgClient(delegate, registry);

        AtomicInteger inProgressCount = new AtomicInteger();
        Thread[] threads = new Thread[6];
        for (int i = 0; i < 6; i++) {
            threads[i] = new Thread(() -> {
                var result = client.lookup("k-" + Math.random());
                if (result.status() == PgClient.LookupStatus.IN_PROGRESS) {
                    inProgressCount.incrementAndGet();
                }
            });
            threads[i].start();
        }

        Thread.sleep(300);
        delegate.releaseLatch();
        for (Thread t : threads) t.join(2000);

        // bulkhead full 이면 lookup 은 IN_PROGRESS 로 fallback (reconciler 가 다음 사이클에 retry).
        assertThat(inProgressCount.get()).isGreaterThan(0);
    }

    private static Money money(long amount) {
        return Money.of(BigDecimal.valueOf(amount), Currency.getInstance("KRW"));
    }

    /**
     * 호출 시 latch 가 release 될 때까지 무한 대기 — bulkhead 의 worker 가 점유된 상태를 시뮬레이션.
     */
    private static class RecordingPgClient implements PgClient {
        private CountDownLatch latch;
        private final AtomicInteger callCount = new AtomicInteger();

        void blockUntilLatch() {
            this.latch = new CountDownLatch(1);
        }

        void releaseLatch() {
            if (latch != null) latch.countDown();
        }

        private void waitIfNeeded() {
            try {
                if (latch != null) latch.await(5, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public AuthorizeResult authorize(AuthorizeRequest request) {
            waitIfNeeded();
            return AuthorizeResult.approved("pg-" + callCount.incrementAndGet());
        }

        @Override
        public RefundResult refund(RefundRequest request) {
            waitIfNeeded();
            return RefundResult.approved("rf-" + callCount.incrementAndGet());
        }

        @Override
        public LookupResult lookup(String idempotencyKey) {
            waitIfNeeded();
            return LookupResult.approved("lk-" + callCount.incrementAndGet());
        }
    }
}
