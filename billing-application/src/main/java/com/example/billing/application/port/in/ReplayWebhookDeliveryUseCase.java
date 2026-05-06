package com.example.billing.application.port.in;

import com.example.billing.domain.webhook.WebhookDeliveryId;

/**
 * 운영자가 dead-lettered delivery 를 수동으로 다시 큐에 넣음.
 *
 * <p>예: customer 측에서 "아까 안 받았던 webhook 다시 보내달라" 요청 → 운영자가 dead letter
 * 화면에서 replay 버튼. 도메인의 {@link com.example.billing.domain.webhook.WebhookDelivery#replay} 가
 * attemptCount 를 한도 미만으로 낮춰 1회 추가 시도 보장.</p>
 */
public interface ReplayWebhookDeliveryUseCase {
    void replay(WebhookDeliveryId deliveryId);
}
