package com.example.billing.adapter.out.webhook;

import com.example.billing.application.port.out.WebhookHttpClient;
import com.example.billing.domain.webhook.WebhookDelivery;
import com.example.billing.domain.webhook.WebhookEndpoint;
import com.example.billing.domain.webhook.WebhookSignature;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Duration;

/**
 * RestClient 기반 webhook HTTP 송신.
 *
 * <p><b>왜 timeout 짧게</b>: customer 서버가 응답이 늦으면 worker 가 묶여 다음 delivery 처리가
 * 밀린다. 5초 connect / 10초 read 면 정상 customer 는 충분, 느린 customer 는 빠르게 retry 큐로.
 *
 * <p><b>왜 라이브러리 retry 안 쓰나</b>: webhook 의 retry 는 *delivery 단위 영속화* + *backoff
 * 정책* 이라 도메인이 책임. 라이브러리 retry (Resilience4j Retry) 는 단일 메서드 호출 안에서
 * 즉시 재시도라 우리 모델 (1분 → 5분 → 30분) 과 안 맞음.
 *
 * <p><b>HTTP status → Outcome 매핑</b>:
 * <ul>
 *   <li>2xx → Success</li>
 *   <li>408 Request Timeout, 429 Too Many Requests, 5xx → Retryable (잠깐 후 재시도)</li>
 *   <li>나머지 4xx (400, 401, 404, 410 등) → Dead (재시도 무의미)</li>
 *   <li>예외 (timeout / DNS / connection refused) → Retryable (네트워크 문제로 가정)</li>
 * </ul>
 */
@Component
@Slf4j
public class RestClientWebhookHttpClient implements WebhookHttpClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

    private final RestClient restClient;
    private final Clock clock;

    public RestClientWebhookHttpClient(RestClient.Builder builder, Clock clock) {
        this.restClient = builder
                .requestFactory(timeoutRequestFactory())
                .build();
        this.clock = clock;
    }

    @Override
    public Outcome send(WebhookEndpoint endpoint, WebhookDelivery delivery) {
        long timestamp = clock.instant().getEpochSecond();
        // grace window 안이면 두 secret 으로 각각 서명한 두 값을 같은 헤더에 콤마 구분으로 실음.
        // Stripe 의 t=12345,v1=newhash,v1=oldhash 와 같은 의도 — customer 가 두 값 중 어느 것이든
        // 자기 측 secret 으로 일치하면 통과. ADR-0029 참고.
        String signatureHeader = endpoint.activeSecrets(clock).stream()
                .map(secret -> WebhookSignature.sign(secret, timestamp, delivery.payload()))
                .reduce((a, b) -> a + "," + b)
                .orElseThrow();   // activeSecrets 는 항상 최소 1 — 비어 있으면 invariant 깨짐.

        try {
            ResponseEntity<String> response = restClient.post()
                    .uri(endpoint.url())
                    .contentType(MediaType.APPLICATION_JSON)
                    // 헤더 — customer 서버가 보고 검증할 정보
                    .header("X-Webhook-Signature", signatureHeader)
                    .header("X-Webhook-Timestamp", String.valueOf(timestamp))
                    .header("X-Webhook-Event", delivery.eventType())
                    .header("X-Webhook-Delivery-Id", delivery.id().toString())
                    // 클라이언트가 멱등 처리하는데 도움 — 같은 delivery id 면 한 번만 처리
                    .header("Idempotency-Key", delivery.id().toString())
                    .body(delivery.payload())
                    .retrieve()
                    .toEntity(String.class);

            int code = response.getStatusCode().value();
            return new Outcome.Success(code);

        } catch (org.springframework.web.client.HttpStatusCodeException ex) {
            int code = ex.getStatusCode().value();
            String body = excerpt(ex.getResponseBodyAsString());
            if (isRetryableStatus(code)) {
                return new Outcome.Retryable(code, body);
            }
            return new Outcome.Dead(code, body);

        } catch (ResourceAccessException ex) {
            // 네트워크 / DNS / connection refused / read timeout — 모두 일시 장애로 간주
            return new Outcome.Retryable(null, "network: " + ex.getMessage());

        } catch (RuntimeException ex) {
            // 알 수 없는 에러 — 일단 재시도. 영구 실패면 결국 MAX_ATTEMPTS 후 dead 로.
            log.warn("unexpected webhook send error endpoint={} delivery={}",
                    endpoint.id(), delivery.id(), ex);
            return new Outcome.Retryable(null, "unexpected: " + ex.getMessage());
        }
    }

    private static boolean isRetryableStatus(int code) {
        if (code >= 500) return true;     // 5xx — 서버측 일시 장애
        if (code == 408) return true;     // Request Timeout
        if (code == 429) return true;     // Too Many Requests
        return false;
    }

    private static String excerpt(String body) {
        if (body == null) return null;
        return body.length() <= 256 ? body : body.substring(0, 256);
    }

    private static org.springframework.http.client.SimpleClientHttpRequestFactory timeoutRequestFactory() {
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) CONNECT_TIMEOUT.toMillis());
        factory.setReadTimeout((int) READ_TIMEOUT.toMillis());
        return factory;
    }
}
