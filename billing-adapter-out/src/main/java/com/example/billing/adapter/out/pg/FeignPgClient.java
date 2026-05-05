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
 * 운영용 PG client. wallet.pg.enabled=true 일 때 활성.
 *
 * <p>Resilience4j {@code @CircuitBreaker} + {@code @Retry} 적용 — application.yml 의
 * {@code resilience4j.circuitbreaker.instances.pg} 설정을 사용. CB open 시 fallback 으로 즉시 reject.</p>
 *
 * <p>OpenFeign 대신 RestClient (Spring 6.1+) 사용 — 더 모던하고 가상스레드 친화적.</p>
 */
@Component
@ConditionalOnProperty(name = "wallet.pg.enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class FeignPgClient implements PgClient {

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
}
