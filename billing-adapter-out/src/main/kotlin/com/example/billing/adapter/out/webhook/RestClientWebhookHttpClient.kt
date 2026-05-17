package com.example.billing.adapter.out.webhook

import com.example.billing.application.port.out.WebhookHttpClient
import com.example.billing.application.port.out.WebhookHttpClient.Outcome
import com.example.billing.domain.webhook.WebhookDelivery
import com.example.billing.domain.webhook.WebhookEndpoint
import com.example.billing.domain.webhook.WebhookSignature
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.HttpStatusCodeException
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import java.time.Clock
import java.time.Duration

/**
 * RestClient 기반 webhook HTTP 송신.
 *
 * 왜 timeout 짧게: customer 서버가 응답이 늦으면 worker 가 묶여 다음 delivery 처리가
 * 밀린다. 5초 connect / 10초 read 면 정상 customer 는 충분, 느린 customer 는 빠르게 retry 큐로.
 *
 * 왜 라이브러리 retry 안 쓰나: webhook 의 retry 는 delivery 단위 영속화 + backoff
 * 정책이라 도메인이 책임. 라이브러리 retry (Resilience4j Retry) 는 단일 메서드 호출 안에서
 * 즉시 재시도라 우리 모델 (1분 → 5분 → 30분) 과 안 맞음.
 *
 * HTTP status → Outcome 매핑:
 *  - 2xx → Success
 *  - 408 Request Timeout, 429 Too Many Requests, 5xx → Retryable (잠깐 후 재시도)
 *  - 나머지 4xx (400, 401, 404, 410 등) → Dead (재시도 무의미)
 *  - 예외 (timeout / DNS / connection refused) → Retryable (네트워크 문제로 가정)
 */
@Component
class RestClientWebhookHttpClient(
    builder: RestClient.Builder,
    private val clock: Clock,
) : WebhookHttpClient {

    private val restClient: RestClient = builder
        .requestFactory(timeoutRequestFactory())
        .build()

    override fun send(endpoint: WebhookEndpoint, delivery: WebhookDelivery): Outcome {
        val timestamp = clock.instant().epochSecond
        // grace window 안이면 두 secret 으로 각각 서명한 두 값을 같은 헤더에 콤마 구분으로 실음.
        // 한 헤더 안에 콤마로 두 서명을 결합하는 webhook 표준 형식 — customer 가 두 값 중 어느
        // 것이든 자기 측 secret 으로 일치하면 통과. ADR-0029 참고.
        val signatureHeader = endpoint.activeSecrets(clock).asSequence()
            .map { secret -> WebhookSignature.sign(secret, timestamp, delivery.payload) }
            .reduce { a, b -> "$a,$b" }   // activeSecrets 는 항상 최소 1 — 비어 있으면 invariant 깨짐.

        return try {
            val response = restClient.post()
                .uri(endpoint.url)
                .contentType(MediaType.APPLICATION_JSON)
                // 헤더 — customer 서버가 보고 검증할 정보
                .header("X-Webhook-Signature", signatureHeader)
                .header("X-Webhook-Timestamp", timestamp.toString())
                .header("X-Webhook-Event", delivery.eventType)
                .header("X-Webhook-Delivery-Id", delivery.id.toString())
                // 클라이언트가 멱등 처리하는데 도움 — 같은 delivery id 면 한 번만 처리
                .header("Idempotency-Key", delivery.id.toString())
                .body(delivery.payload)
                .retrieve()
                .toEntity(String::class.java)

            val code = response.statusCode.value()
            Outcome.Success(code)
        } catch (ex: HttpStatusCodeException) {
            val code = ex.statusCode.value()
            val body = excerpt(ex.responseBodyAsString) ?: ""
            if (isRetryableStatus(code)) {
                Outcome.Retryable(code, body)
            } else {
                Outcome.Dead(code, body)
            }
        } catch (ex: ResourceAccessException) {
            // 네트워크 / DNS / connection refused / read timeout — 모두 일시 장애로 간주
            Outcome.Retryable(null, "network: ${ex.message}")
        } catch (ex: RuntimeException) {
            // 알 수 없는 에러 — 일단 재시도. 영구 실패면 결국 MAX_ATTEMPTS 후 dead 로.
            log.warn(
                "unexpected webhook send error endpoint={} delivery={}",
                endpoint.id, delivery.id, ex,
            )
            Outcome.Retryable(null, "unexpected: ${ex.message}")
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(RestClientWebhookHttpClient::class.java)

        private val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(5)
        private val READ_TIMEOUT: Duration = Duration.ofSeconds(10)

        private fun isRetryableStatus(code: Int): Boolean {
            if (code >= 500) return true     // 5xx — 서버측 일시 장애
            if (code == 408) return true     // Request Timeout
            if (code == 429) return true     // Too Many Requests
            return false
        }

        private fun excerpt(body: String?): String? {
            if (body == null) return null
            return if (body.length <= 256) body else body.substring(0, 256)
        }

        private fun timeoutRequestFactory(): SimpleClientHttpRequestFactory {
            val factory = SimpleClientHttpRequestFactory()
            factory.setConnectTimeout(CONNECT_TIMEOUT.toMillis().toInt())
            factory.setReadTimeout(READ_TIMEOUT.toMillis().toInt())
            return factory
        }
    }
}
