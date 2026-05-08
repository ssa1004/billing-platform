package com.example.billing.adapter.out.pg;

import com.example.billing.application.port.out.PgClient;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 운영용 PG (외부 결제 게이트웨이) 클라이언트. billing.pg.enabled=true 일 때 활성.
 *
 * <p>Resilience4j 의 {@code @CircuitBreaker} (실패가 누적되면 호출 자체를 잠시 차단) +
 * {@code @Retry} (일시 장애 흡수용 재시도) 를 적용합니다 — application.yml 의
 * {@code resilience4j.circuitbreaker.instances.pg} 설정을 사용. 서킷 브레이커가 OPEN 되면
 * fallback 메서드로 즉시 reject 응답을 돌려줍니다.</p>
 *
 * <p>Spring RestClient 로 HTTP 호출을 명시적으로 구성합니다. 가상 스레드 (Java 21 Virtual
 * Threads) 환경에서도 블로킹 호출 모델을 단순하게 유지할 수 있습니다.</p>
 */
@Component
@ConditionalOnProperty(name = "billing.pg.enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class RestClientPgClient implements PgClient {

    private final RestClient pgRestClient;

    @Override
    @CircuitBreaker(name = "pg", fallbackMethod = "authorizeFallback")
    @Retry(name = "pg")
    public AuthorizeResult authorize(AuthorizeRequest req) {
        return pgRestClient.post()
                .uri("/v1/payments/authorize")
                .body(req)
                .retrieve()
                .body(AuthorizeResult.class);
    }

    @SuppressWarnings("unused")
    private AuthorizeResult authorizeFallback(AuthorizeRequest req, Throwable t) {
        log.warn("[pg] authorize fallback triggered: {}", t.getMessage());
        return AuthorizeResult.rejected("CB_OPEN", "PG unavailable: " + t.getMessage());
    }

    @Override
    @CircuitBreaker(name = "pg", fallbackMethod = "refundFallback")
    @Retry(name = "pg")
    public RefundResult refund(RefundRequest req) {
        return pgRestClient.post()
                .uri("/v1/payments/refund")
                .body(req)
                .retrieve()
                .body(RefundResult.class);
    }

    @SuppressWarnings("unused")
    private RefundResult refundFallback(RefundRequest req, Throwable t) {
        log.warn("[pg] refund fallback triggered: {}", t.getMessage());
        return RefundResult.rejected("PG unavailable: " + t.getMessage());
    }

    /**
     * PG 측 idempotency key 단위 결과 조회. 3-phase 흐름의 phase 3 (DB tx2) 가 깨졌을 때
     * reconciler 가 사용. CB / Retry 동일 인스턴스 (pg) 사용 — 운영 시 lookup 전용 인스턴스로
     * 분리해도 됨.
     *
     * <p>404 (PG 에 키 없음) 는 비즈니스 의미 (= 처리 안 됨) 라 fallback 이 아니라 정상 path 로
     * NOT_FOUND 변환해서 돌려준다.</p>
     */
    @Override
    @CircuitBreaker(name = "pg", fallbackMethod = "lookupFallback")
    @Retry(name = "pg")
    public LookupResult lookup(String idempotencyKey) {
        try {
            return pgRestClient.get()
                    .uri("/v1/payments/lookup/{key}", idempotencyKey)
                    .retrieve()
                    .body(LookupResult.class);
        } catch (org.springframework.web.client.HttpClientErrorException.NotFound e) {
            return LookupResult.notFound();
        }
    }

    @SuppressWarnings("unused")
    private LookupResult lookupFallback(String idempotencyKey, Throwable t) {
        // CB OPEN / 네트워크 에러 — 결과 모름 → IN_PROGRESS 로 처리해 다음 사이클에 재조회.
        log.warn("[pg] lookup fallback triggered key={} reason={}", idempotencyKey, t.getMessage());
        return LookupResult.inProgress();
    }
}
