package com.example.billing.adapter.out.notification;

import com.example.billing.application.port.out.CustomerNotifier;
import com.example.billing.domain.shared.CustomerId;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Webhook 기반 알림 — customer 가 등록한 callback URL 로 HTTP POST 발송.
 *
 * <p>Resilience4j 로 재시도 (retry) + 서킷 브레이커 (circuit breaker, 실패 누적 시 호출 자체를
 * 잠시 차단). 실패한 알림은 DLQ (Dead Letter Queue) 같은 별도 저장소에 기록 (현재 구현은
 * 생략).</p>
 *
 * <p>운영에서는 customer 별 webhook URL 매핑이 별도 store 에 있어야 합니다 (CustomerProfile).
 * 본 구현은 단순화를 위해 모두 같은 URL 로 보내는 stub.</p>
 */
@Component
@Profile("prod")
@ConfigurationProperties(prefix = "billing.notification.webhook")
public class WebhookCustomerNotifier implements CustomerNotifier {

    private static final Logger log = LoggerFactory.getLogger(WebhookCustomerNotifier.class);

    private final RestClient client;
    private final ObjectMapper objectMapper;
    private String defaultUrl;

    public WebhookCustomerNotifier(RestClient.Builder builder, ObjectMapper objectMapper) {
        this.client = builder.build();
        this.objectMapper = objectMapper;
    }

    public void setDefaultUrl(String defaultUrl) { this.defaultUrl = defaultUrl; }

    @Override
    @Retry(name = "customer-notifier")
    @CircuitBreaker(name = "customer-notifier")
    public void notify(CustomerId customerId, NotificationType type,
                       Map<String, Object> context) {
        Map<String, Object> body = new HashMap<>();
        body.put("customerId", customerId.value());
        body.put("type", type.name());
        body.put("occurredAt", Instant.now().toString());
        body.put("context", context);

        try {
            String json = objectMapper.writeValueAsString(body);
            client.post()
                    .uri(defaultUrl)
                    .header("Content-Type", "application/json")
                    .header("X-Billing-Notification-Type", type.name())
                    .body(json)
                    .retrieve()
                    .toBodilessEntity();
        } catch (JsonProcessingException e) {
            log.warn("notification serialization failed for {} {}", customerId, type, e);
        }
    }
}
