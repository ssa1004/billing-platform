package com.example.billing.application.service;

import com.example.billing.application.exception.WebhookEndpointNotFoundException;
import com.example.billing.application.port.in.WebhookEndpointLifecycleUseCase;
import com.example.billing.application.port.out.WebhookEndpointRepository;
import com.example.billing.domain.webhook.WebhookEndpoint;
import com.example.billing.domain.webhook.WebhookEndpointId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

@Service
@RequiredArgsConstructor
@Slf4j
public class WebhookEndpointLifecycleService implements WebhookEndpointLifecycleUseCase {

    private final WebhookEndpointRepository endpoints;
    private final Clock clock;

    @Override
    @Transactional
    public void pause(WebhookEndpointId endpointId) {
        WebhookEndpoint endpoint = endpoints.findById(endpointId)
                .orElseThrow(() -> new WebhookEndpointNotFoundException(endpointId));
        endpoint.pause(clock);
        endpoints.save(endpoint);
        log.info("webhook endpoint paused id={}", endpointId);
    }

    @Override
    @Transactional
    public void resume(WebhookEndpointId endpointId) {
        WebhookEndpoint endpoint = endpoints.findById(endpointId)
                .orElseThrow(() -> new WebhookEndpointNotFoundException(endpointId));
        endpoint.resume(clock);
        endpoints.save(endpoint);
        log.info("webhook endpoint resumed id={}", endpointId);
    }

    @Override
    @Transactional
    public String rotateSecret(WebhookEndpointId endpointId) {
        WebhookEndpoint endpoint = endpoints.findById(endpointId)
                .orElseThrow(() -> new WebhookEndpointNotFoundException(endpointId));
        endpoint.rotateSecret(clock);
        endpoints.save(endpoint);
        log.info("webhook endpoint secret rotated id={}", endpointId);
        return endpoint.secret();
    }
}
