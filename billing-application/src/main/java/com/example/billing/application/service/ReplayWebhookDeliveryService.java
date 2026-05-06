package com.example.billing.application.service;

import com.example.billing.application.exception.WebhookDeliveryNotFoundException;
import com.example.billing.application.port.in.ReplayWebhookDeliveryUseCase;
import com.example.billing.application.port.out.WebhookDeliveryRepository;
import com.example.billing.domain.webhook.WebhookDelivery;
import com.example.billing.domain.webhook.WebhookDeliveryId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

@Service
@RequiredArgsConstructor
@Slf4j
public class ReplayWebhookDeliveryService implements ReplayWebhookDeliveryUseCase {

    private final WebhookDeliveryRepository deliveries;
    private final Clock clock;

    @Override
    @Transactional
    public void replay(WebhookDeliveryId deliveryId) {
        WebhookDelivery delivery = deliveries.findById(deliveryId)
                .orElseThrow(() -> new WebhookDeliveryNotFoundException(deliveryId));
        delivery.replay(clock);
        deliveries.save(delivery);
        log.info("webhook delivery replayed id={} attemptsSoFar={}", deliveryId, delivery.attemptCount());
    }
}
