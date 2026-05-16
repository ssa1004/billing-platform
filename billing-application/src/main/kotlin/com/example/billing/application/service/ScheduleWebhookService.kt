package com.example.billing.application.service

import com.example.billing.application.command.ScheduleWebhookCommand
import com.example.billing.application.port.`in`.ScheduleWebhookUseCase
import com.example.billing.application.port.out.WebhookDeliveryRepository
import com.example.billing.application.port.out.WebhookEndpointRepository
import com.example.billing.domain.webhook.WebhookDelivery
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/**
 * 도메인 이벤트 → 구독 endpoint 들의 delivery 생성 (큐 등록).
 *
 * **흐름**:
 *  1. customer 의 ACTIVE endpoint 들 조회
 *  2. 각 endpoint 가 이 event_type 을 구독하는지 체크 (빈 set = 전체)
 *  3. 해당 endpoint 마다 delivery 1개씩 schedule (PENDING, nextAttemptAt = now)
 *
 * **왜 동기적으로 생성하나**: 도메인 이벤트 발생 트랜잭션 안에서 delivery row 까지 같이
 * INSERT → 외부에서 보면 이벤트 발생 = 알림 큐에 들어감 이 원자적. 실제 HTTP 발송은
 * 별도 worker 가 처리.
 */
@Service
open class ScheduleWebhookService(
    private val endpoints: WebhookEndpointRepository,
    private val deliveries: WebhookDeliveryRepository,
    private val clock: Clock,
) : ScheduleWebhookUseCase {

    @Transactional
    override fun schedule(command: ScheduleWebhookCommand): Int {
        val activeEndpoints = endpoints.findActiveByCustomer(command.customerId)
        var scheduled = 0
        for (endpoint in activeEndpoints) {
            if (!endpoint.subscribesTo(command.eventType)) continue
            val delivery = WebhookDelivery.schedule(
                endpoint.id, command.eventType, command.payload, clock,
            )
            deliveries.save(delivery)
            scheduled++
        }
        if (scheduled > 0) {
            log.debug(
                "scheduled {} webhook deliveries for customer={} event={}",
                scheduled, command.customerId, command.eventType,
            )
        }
        return scheduled
    }

    companion object {
        private val log = LoggerFactory.getLogger(ScheduleWebhookService::class.java)
    }
}
