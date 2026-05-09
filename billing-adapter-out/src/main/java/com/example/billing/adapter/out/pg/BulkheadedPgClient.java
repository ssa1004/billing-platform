package com.example.billing.adapter.out.pg;

import com.example.billing.application.port.out.PgClient;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.bulkhead.ThreadPoolBulkhead;
import io.github.resilience4j.bulkhead.ThreadPoolBulkheadRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * PG 호출용 ThreadPool Bulkhead (격리 풀) 데코레이터.
 *
 * <p><b>왜 필요</b>: 현재 PG 호출은 servlet thread (가상 스레드 포함) 위에서 직접 실행됩니다.
 * PG 가 슬로우다운되면 servlet thread pool 이 PG 응답 대기로 점유되어 *다른 endpoint
 * (wallet 조회, invoice 발급, audit 조회 등) 까지 stall* 됩니다. 분산 시스템의 고전적
 * cascade failure (한 종속이 느려져 caller 까지 함께 무너지는 현상) — 도메인별 ThreadPool
 * 격리 (bulkhead 패턴) 가 표준 처방입니다.</p>
 *
 * <p><b>흐름</b>:</p>
 * <pre>
 *   Application service (servlet thread)
 *      │
 *      ▼  submit
 *   ThreadPoolBulkhead "pg" (격리된 worker pool)
 *      │   ├─ 가득 차면 → BulkheadFullException (fast-fail, 503)
 *      │   └─ 여유 있으면 worker 가 호출 실행
 *      ▼
 *   RestClientPgClient (CB + Retry — ADR-0008)
 *      ▼
 *   외부 PG HTTP
 * </pre>
 *
 * <p><b>왜 ThreadPool 격리가 Semaphore 보다 좋은가</b>: Semaphore 는 *호출 스레드 자체* 의
 * 동시성만 제한 — slow call 자체는 막지 않음 (스레드는 여전히 PG 응답 대기로 묶임).
 * ThreadPool 은 *별도 worker* 가 호출을 실행 → caller 는 future 만 받고 짧은 timeout 으로 wait,
 * worker 가 막혀도 caller 자체는 풀림. 따라서 도메인 간 장애 전파에 강함.</p>
 *
 * <p><b>호출 인터페이스는 동기 그대로 유지</b>: 가상 스레드 (Java 21 Virtual Threads) 환경에선
 * caller 가 잠시 wait 해도 OS thread 는 안 잡힘 — 동기 인터페이스 + bulkhead 격리만으로 충분한
 * 전파 차단 효과. CompletableFuture 를 application service 까지 끌고 가는 큰 변경 없이 효과만
 * 가져옵니다. ADR-0026 참고.</p>
 *
 * <p><b>Pool 산정 (Little's law: capacity = throughput × latency)</b>: PG authorize TPS 10,
 * latency 평균 200ms (P99 1s) 라면 동시 호출 ≈ 10. 안전 marg 곱해 maxThreadPoolSize=20,
 * coreThreadPoolSize=10, queueCapacity=50 (peak burst 흡수). 운영 metric 으로 조정.</p>
 *
 * <p><b>활성 조건</b>: {@code billing.pg.bulkhead-enabled=true} (기본값 true). false 면 빈 자체가
 * 등록 안 되어 RestClientPgClient 가 직접 @Primary 가 되어 그대로 사용됨.</p>
 */
@Component
@Primary
@ConditionalOnProperty(
        // RestClientPgClient 가 활성일 때만 의미. MockPgClient (billing.pg.enabled=false) 환경에선
        // bulkhead 자체가 disable — Mock 호출은 PG 의 자원 보호 의미가 없음.
        name = {"billing.pg.enabled", "billing.pg.bulkhead-enabled"},
        havingValue = "true",
        matchIfMissing = false
)
@Slf4j
public class BulkheadedPgClient implements PgClient {

    private final PgClient delegate;
    private final ThreadPoolBulkhead bulkhead;
    private final long submissionTimeoutMs;

    public BulkheadedPgClient(@Qualifier("restClientPgClient") PgClient delegate,
                              ThreadPoolBulkheadRegistry bulkheadRegistry) {
        this.delegate = delegate;
        this.bulkhead = bulkheadRegistry.bulkhead("pg");
        // PG 평균 latency 200ms + retry 3회 (200/400/800ms) + buffer ≈ 6s.
        this.submissionTimeoutMs = 6000L;
    }

    @Override
    public AuthorizeResult authorize(AuthorizeRequest request) {
        return executeOnBulkhead(() -> delegate.authorize(request),
                "authorize", () -> AuthorizeResult.rejected("BULKHEAD_FULL", "PG bulkhead full, retry later"));
    }

    @Override
    public RefundResult refund(RefundRequest request) {
        return executeOnBulkhead(() -> delegate.refund(request),
                "refund", () -> RefundResult.rejected("PG bulkhead full, retry later"));
    }

    @Override
    public LookupResult lookup(String idempotencyKey) {
        // lookup 은 reconciler 가 부르는 idempotent 조회 — bulkhead full 시 다음 사이클에 다시
        // 시도하면 되므로 IN_PROGRESS 로 처리 (reconciler 가 retry).
        return executeOnBulkhead(() -> delegate.lookup(idempotencyKey),
                "lookup", LookupResult::inProgress);
    }

    /**
     * Bulkhead 의 격리 worker 풀에서 supplier 를 실행. 가득 차면 즉시 fallback.
     *
     * <p>호출 servlet thread 는 {@code submissionTimeoutMs} 만큼만 결과를 기다립니다 — 그 안에
     * worker 가 응답을 못 만들면 timeout fallback. 이 내부 timeout 이 RestClient 자체의 read
     * timeout (5s) + retry 합산 (총 ~ 6s) 과 같은 수준이라 경합/이중 timeout 방지.</p>
     */
    private <T> T executeOnBulkhead(Supplier<T> task, String op, Supplier<T> fallback) {
        try {
            return bulkhead.executeSupplier(task)
                    .toCompletableFuture()
                    .get(submissionTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (BulkheadFullException e) {
            log.warn("[pg] bulkhead full op={} reason={}", op, e.getMessage());
            return fallback.get();
        } catch (TimeoutException e) {
            log.warn("[pg] bulkhead submission timeout op={} after={}ms", op, submissionTimeoutMs);
            return fallback.get();
        } catch (ExecutionException e) {
            // 내부 호출에서 던진 RuntimeException 을 그대로 propagation — CB 의 fallback 이 이미
            // 정상 응답으로 변환했거나, application service 가 처리해야 할 도메인 예외.
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) throw re;
            throw new RuntimeException(cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[pg] bulkhead interrupted op={}", op);
            return fallback.get();
        }
    }
}
