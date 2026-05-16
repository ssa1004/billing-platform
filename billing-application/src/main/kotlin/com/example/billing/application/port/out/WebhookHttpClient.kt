package com.example.billing.application.port.out

import com.example.billing.domain.webhook.WebhookDelivery
import com.example.billing.domain.webhook.WebhookEndpoint

/**
 * Webhook HTTP 발송 — 어댑터가 실제 HTTP 콜.
 *
 * 도메인이 어떤 HTTP 라이브러리를 쓰는지 모르게 분리. 어댑터 (Resilience4j + Spring
 * RestClient) 가 timeout / retry-policy / circuit breaker / TLS / DNS 같은 인프라를 책임.
 *
 * 결과는 [Outcome] 의 3가지 — 어떤 HTTP status 가 retry 인지 는 어댑터가 결정해서
 * 도메인에 알려줌. (도메인은 "다시 시도해야 하나?" 만 알면 됨.)
 */
interface WebhookHttpClient {

    fun send(endpoint: WebhookEndpoint, delivery: WebhookDelivery): Outcome

    sealed interface Outcome {

        /** 2xx 성공. 종착. */
        @JvmRecord
        data class Success(val httpStatus: Int) : Outcome

        /** 5xx / timeout / network — 잠시 후 재시도. `httpStatus` null = 응답 못 받음. */
        @JvmRecord
        data class Retryable(val httpStatus: Int?, val summary: String) : Outcome

        /** 4xx 등 영구 실패 — 재시도 무의미. (단 408/429 는 어댑터가 Retryable 로 분류해야 함.) */
        @JvmRecord
        data class Dead(val httpStatus: Int, val summary: String) : Outcome
    }
}
