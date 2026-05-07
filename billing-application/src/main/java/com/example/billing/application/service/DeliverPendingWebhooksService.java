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
 * 워커 — PENDING delivery 를 잡아서 HTTP 발송 → 결과에 따라 SUCCESS / 다시 PENDING /
 * DEAD_LETTERED 로 처리.
 *
 * <p><b>왜 한 트랜잭션</b>: {@code claimPending} 이 잡은 `SKIP LOCKED` 락을 *발송 + save*
 * 까지 유지하기 위함. 트랜잭션 밖에서 발송하면 락이 먼저 풀려 다른 워커가 같은 row 를 또 잡을
 * 수 있어 이중 발송이 발생합니다.
 *
 * <p><b>왜 한 batch 의 size 를 작게 잡나</b>: 한 트랜잭션이 길어지면 lock 보유 시간이 길어지고
 * 다른 워커 / 운영자 작업이 막힙니다. 5~10건이 보통. 실제 발송은 customer 서버 응답 대기
 * (~1~10초) 라 트랜잭션이 그만큼 살아 있어야 함 — DB connection pool (DB 연결 풀) 압박도
 * 고려해야 합니다.
 *
 * <p><b>왜 트랜잭션 안에서 HTTP 호출?</b> connection pool 압박이 있긴 하지만, 다른 선택은
 * "PENDING → IN_FLIGHT 만 먼저 마킹 + 트랜잭션 종료, HTTP 호출은 별도 트랜잭션" 입니다.
 * 이 방식은 워커가 IN_FLIGHT 후 죽으면 영원히 IN_FLIGHT 로 남아 별도 timeout 복구 매커니즘이
 * 필요해집니다. 단순화를 위해 "한 트랜잭션 + 짧은 timeout" 으로 갑니다. 트래픽이 늘면 분리
 * 검토.
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
            // endpoint 조회 — endpoint 가 사라졌으면 (삭제됨) 더 보낼 곳이 없으므로 dead 처리
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
