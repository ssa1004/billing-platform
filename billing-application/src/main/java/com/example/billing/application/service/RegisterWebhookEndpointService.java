package com.example.billing.application.service;

import com.example.billing.application.command.RegisterWebhookEndpointCommand;
import com.example.billing.application.port.in.RegisterWebhookEndpointUseCase;
import com.example.billing.application.port.out.IdempotencyKeyStore;
import com.example.billing.application.port.out.WebhookEndpointRepository;
import com.example.billing.domain.shared.CustomerId;
import com.example.billing.domain.webhook.WebhookEndpoint;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

/**
 * Customer 가 webhook 수신 endpoint 등록.
 *
 * <p>secret 은 도메인이 자동 생성 (호출자가 직접 정하지 않음). 응답 한 번만 평문 노출 →
 * REST 컨트롤러가 그 응답에서 secret 을 customer 에게 표시 → 이후 모든 조회에서는 hash 만.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RegisterWebhookEndpointService implements RegisterWebhookEndpointUseCase {

    private final WebhookEndpointRepository endpoints;
    private final IdempotencyKeyStore idempotencyKeys;
    private final Clock clock;

    @Override
    @Transactional
    public WebhookEndpoint register(RegisterWebhookEndpointCommand cmd) {
        idempotencyKeys.acquireOrThrow(cmd.idempotencyKey());

        WebhookEndpoint endpoint = WebhookEndpoint.register(
                CustomerId.of(cmd.customerId()),
                cmd.url(),
                cmd.subscribedEventTypes(),
                clock
        );
        endpoints.save(endpoint);
        log.info("webhook endpoint registered id={} customer={} url={} events={}",
                endpoint.id(), endpoint.customerId(), endpoint.url(),
                endpoint.subscribedEventTypes().isEmpty() ? "ALL" : endpoint.subscribedEventTypes());
        return endpoint;
    }
}
