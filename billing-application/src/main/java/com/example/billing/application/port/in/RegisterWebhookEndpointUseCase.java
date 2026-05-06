package com.example.billing.application.port.in;

import com.example.billing.application.command.RegisterWebhookEndpointCommand;
import com.example.billing.domain.webhook.WebhookEndpoint;

public interface RegisterWebhookEndpointUseCase {

    /**
     * 등록 후 한 번만 평문 secret 이 응답에 포함된다 — customer 가 자기 검증 코드에 즉시 반영해야.
     * 이후 GET 등 다른 조회에선 *반환 안 함*. 분실하면 rotateSecret 로 갱신.
     */
    WebhookEndpoint register(RegisterWebhookEndpointCommand command);
}
