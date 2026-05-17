package com.example.billing.adapter.out.pg

import com.example.billing.application.port.out.PgClient
import com.example.billing.application.port.out.PgClient.AuthorizeRequest
import com.example.billing.application.port.out.PgClient.AuthorizeResult
import com.example.billing.application.port.out.PgClient.LookupResult
import com.example.billing.application.port.out.PgClient.RefundRequest
import com.example.billing.application.port.out.PgClient.RefundResult
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker
import io.github.resilience4j.retry.annotation.Retry
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestClient

/**
 * 운영용 PG (외부 결제 게이트웨이) 클라이언트. billing.pg.enabled=true 일 때 활성.
 *
 * Resilience4j 의 `@CircuitBreaker` (실패가 누적되면 호출 자체를 잠시 차단) +
 * `@Retry` (일시 장애 흡수용 재시도) 를 적용합니다 — application.yml 의
 * `resilience4j.circuitbreaker.instances.pg` 설정을 사용. 서킷 브레이커가 OPEN 되면
 * fallback 메서드로 즉시 reject 응답을 돌려줍니다.
 *
 * Spring RestClient 로 HTTP 호출을 명시적으로 구성합니다. 가상 스레드 (Java 21 Virtual
 * Threads) 환경에서도 블로킹 호출 모델을 단순하게 유지할 수 있습니다.
 *
 * `open class` — Resilience4j AOP / Spring proxy 가 메서드를 가로채려면 open 이 필요.
 * plugin.spring 이 자동으로 처리하지만 fallbackMethod 명시 호출 호환을 위해 클래스 자체 open.
 */
@Component
@ConditionalOnProperty(name = ["billing.pg.enabled"], havingValue = "true")
open class RestClientPgClient(
    private val pgRestClient: RestClient,
) : PgClient {

    @CircuitBreaker(name = "pg", fallbackMethod = "authorizeFallback")
    @Retry(name = "pg")
    override fun authorize(req: AuthorizeRequest): AuthorizeResult {
        return pgRestClient.post()
            .uri("/v1/payments/authorize")
            .body(req)
            .retrieve()
            .body(AuthorizeResult::class.java)
            ?: AuthorizeResult.rejected("EMPTY_BODY", "PG returned empty body")
    }

    @Suppress("unused")
    private fun authorizeFallback(req: AuthorizeRequest, t: Throwable): AuthorizeResult {
        log.warn("[pg] authorize fallback triggered: {}", t.message)
        return AuthorizeResult.rejected("CB_OPEN", "PG unavailable: ${t.message}")
    }

    @CircuitBreaker(name = "pg", fallbackMethod = "refundFallback")
    @Retry(name = "pg")
    override fun refund(req: RefundRequest): RefundResult {
        return pgRestClient.post()
            .uri("/v1/payments/refund")
            .body(req)
            .retrieve()
            .body(RefundResult::class.java)
            ?: RefundResult.rejected("PG returned empty body")
    }

    @Suppress("unused")
    private fun refundFallback(req: RefundRequest, t: Throwable): RefundResult {
        log.warn("[pg] refund fallback triggered: {}", t.message)
        return RefundResult.rejected("PG unavailable: ${t.message}")
    }

    /**
     * PG 측 idempotency key 단위 결과 조회. 3-phase 흐름의 phase 3 (DB tx2) 가 깨졌을 때
     * reconciler 가 사용. CB / Retry 동일 인스턴스 (pg) 사용 — 운영 시 lookup 전용 인스턴스로
     * 분리해도 됨.
     *
     * 404 (PG 에 키 없음) 는 비즈니스 의미 (= 처리 안 됨) 라 fallback 이 아니라 정상 path 로
     * NOT_FOUND 변환해서 돌려준다.
     */
    @CircuitBreaker(name = "pg", fallbackMethod = "lookupFallback")
    @Retry(name = "pg")
    override fun lookup(idempotencyKey: String): LookupResult {
        return try {
            pgRestClient.get()
                .uri("/v1/payments/lookup/{key}", idempotencyKey)
                .retrieve()
                .body(LookupResult::class.java)
                ?: LookupResult.inProgress()
        } catch (e: HttpClientErrorException.NotFound) {
            LookupResult.notFound()
        }
    }

    @Suppress("unused")
    private fun lookupFallback(idempotencyKey: String, t: Throwable): LookupResult {
        // CB OPEN / 네트워크 에러 — 결과 모름 → IN_PROGRESS 로 처리해 다음 사이클에 재조회.
        log.warn("[pg] lookup fallback triggered key={} reason={}", idempotencyKey, t.message)
        return LookupResult.inProgress()
    }

    companion object {
        private val log = LoggerFactory.getLogger(RestClientPgClient::class.java)
    }
}
