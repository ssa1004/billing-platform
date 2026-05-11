package com.example.billing.application.service;

import com.example.billing.application.command.ScheduleWebhookCommand;
import com.example.billing.application.port.in.ScheduleWebhookUseCase;
import com.example.billing.application.port.out.WebhookDeliveryRepository;
import com.example.billing.application.port.out.WebhookEndpointRepository;
import com.example.billing.domain.webhook.WebhookDelivery;
import com.example.billing.domain.webhook.WebhookEndpoint;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;

/**
 * 도메인 이벤트 → 구독 endpoint 들의 delivery 생성 (큐 등록).
 *
 * <p><b>흐름</b>:
 * <ol>
 *   <li>customer 의 ACTIVE endpoint 들 조회</li>
 *   <li>각 endpoint 가 이 event_type 을 구독하는지 체크 (빈 set = 전체)</li>
 *   <li>해당 endpoint 마다 delivery 1개씩 schedule (PENDING, nextAttemptAt = now)</li>
 * </ol>
 *
 * <p><b>왜 동기적으로 생성하나</b>: 도메인 이벤트 발생 트랜잭션 안에서 delivery row 까지 같이
 * INSERT → 외부에서 보면 이벤트 발생 = 알림 큐에 들어감 이 원자적. 실제 HTTP 발송은
 * 별도 worker 가 처리.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ScheduleWebhookService implements ScheduleWebhookUseCase {

    private final WebhookEndpointRepository endpoints;
    private final WebhookDeliveryRepository deliveries;
    private final Clock clock;

    @Override
    @Transactional
    public int schedule(ScheduleWebhookCommand cmd) {
        List<WebhookEndpoint> activeEndpoints = endpoints.findActiveByCustomer(cmd.customerId());
        int scheduled = 0;
        for (WebhookEndpoint endpoint : activeEndpoints) {
            if (!endpoint.subscribesTo(cmd.eventType())) continue;
            WebhookDelivery delivery = WebhookDelivery.schedule(
                    endpoint.id(), cmd.eventType(), cmd.payload(), clock);
            deliveries.save(delivery);
            scheduled++;
        }
        if (scheduled > 0) {
            log.debug("scheduled {} webhook deliveries for customer={} event={}",
                    scheduled, cmd.customerId(), cmd.eventType());
        }
        return scheduled;
    }
}
