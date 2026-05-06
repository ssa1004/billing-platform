package com.example.billing.application.service;

import com.example.billing.application.port.in.DeliverPendingWebhooksUseCase;
import com.example.billing.application.port.out.WebhookDeliveryRepository;
import com.example.billing.application.port.out.WebhookEndpointRepository;
import com.example.billing.application.port.out.WebhookHttpClient;
import com.example.billing.application.port.out.WebhookHttpClient.Outcome;
import com.example.billing.domain.webhook.WebhookDelivery;
import com.example.billing.domain.webhook.WebhookEndpoint;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 워커 — PENDING delivery 를 잡아서 HTTP 발송 → 결과에 따라 SUCCESS / 재PENDING / DEAD_LETTERED.
 *
 * <p><b>왜 한 트랜잭션</b>: {@code claimPending} 의 SKIP LOCKED 락을 *발송 + save* 까지 유지.
 * 트랜잭션 밖에서 발송하면 락 풀려 다른 워커가 같은 row 잡을 수 있다 (이중 발송).
 *
 * <p><b>왜 한 batch 의 size 를 작게</b>: 한 트랜잭션이 길어지면 lock 보유 시간 길어지고 다른
 * 워커 / 운영자가 막힘. 5~10건이 보통. 실제 발송은 customer 서버 응답 대기 (~1~10초) 라
 * 트랜잭션 lifetime 이 그만큼 길어짐 — DB connection pool 도 신경.
 *
 * <p><b>왜 trans 안에서 HTTP 콜?</b> connection pool 압박이 있긴 하지만, 다른 선택은
 * "PENDING → IN_FLIGHT 만 먼저 마킹 + 트랜잭션 끝, HTTP 콜은 별도 트랜잭션" — 이 경우 워커가
 * IN_FLIGHT 후 죽으면 영원히 IN_FLIGHT 로 남음 (timeout 복구 메커니즘 따로 필요). 단순화 위해
 * "한 트랜잭션 + 짧은 timeout" 으로 간다. 트래픽 늘면 분리 검토.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DeliverPendingWebhooksService implements DeliverPendingWebhooksUseCase {

    private final WebhookDeliveryRepository deliveries;
    private final WebhookEndpointRepository endpoints;
    private final WebhookHttpClient httpClient;
    private final Clock clock;

    @Override
    @Transactional
    public int deliverBatch(int limit) {
        Instant now = clock.instant();
        List<WebhookDelivery> claimed = deliveries.claimPending(now, limit);
        if (claimed.isEmpty()) return 0;

        int processed = 0;
        for (WebhookDelivery delivery : claimed) {
            // endpoint 조회 — 없으면 (삭제됨) skip + dead 처리
            Optional<WebhookEndpoint> endpointOpt = endpoints.findById(delivery.endpointId());
            if (endpointOpt.isEmpty()) {
                log.warn("delivery endpoint vanished delivery={} endpoint={}",
                        delivery.id(), delivery.endpointId());
                delivery.beginAttempt(clock);
                delivery.markDead(null, "endpoint deleted", clock);
                deliveries.save(delivery);
                processed++;
                continue;
            }
            WebhookEndpoint endpoint = endpointOpt.get();
            delivery.beginAttempt(clock);

            Outcome outcome = httpClient.send(endpoint, delivery);
            switch (outcome) {
                case Outcome.Success s -> delivery.markSuccess(s.httpStatus(), clock);
                case Outcome.Retryable r -> delivery.markRetryable(r.httpStatus(), r.summary(), clock);
                case Outcome.Dead d -> delivery.markDead(d.httpStatus(), d.summary(), clock);
            }
            deliveries.save(delivery);
            processed++;
        }
        log.info("webhook delivery batch processed={}/{}", processed, claimed.size());
        return processed;
    }
}
