package com.example.billing.adapter.out.notification

import com.example.billing.application.port.out.CustomerNotifier
import com.example.billing.application.port.out.CustomerNotifier.NotificationType
import com.example.billing.domain.shared.CustomerId
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

/**
 * dev / test 용 — 알림을 로그로만 출력. 실 운영에서는 EmailCustomerNotifier 또는
 * WebhookCustomerNotifier (Resilience4j 적용) 로 교체.
 */
@Component
@Profile("!prod")
class LoggingCustomerNotifier : CustomerNotifier {

    override fun notify(
        customerId: CustomerId,
        type: NotificationType,
        context: Map<String, Any>,
    ) {
        log.info(
            "[notification] customer={} type={} context={}",
            customerId.value, type, context,
        )
    }

    companion object {
        private val log = LoggerFactory.getLogger(LoggingCustomerNotifier::class.java)
    }
}
