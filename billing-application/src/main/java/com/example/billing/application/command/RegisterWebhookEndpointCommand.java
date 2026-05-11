package com.example.billing.application.command;

import java.util.Set;

/**
 * Customer 가 webhook 수신 endpoint 를 등록.
 *
 * <p>{@code subscribedEventTypes} 가 비어 있으면 모든 이벤트 구독 (default).
 * 비추: 처음 통합하는 customer 는 보통 모든 이벤트로 시작 → 익숙해지면 선별.</p>
 */
public record RegisterWebhookEndpointCommand(
        String idempotencyKey,
        String customerId,
        String url,
        Set<String> subscribedEventTypes
) {}
