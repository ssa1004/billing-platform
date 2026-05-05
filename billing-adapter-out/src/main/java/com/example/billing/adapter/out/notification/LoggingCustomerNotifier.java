package com.example.billing.adapter.out.notification;

import com.example.billing.application.port.out.CustomerNotifier;
import com.example.billing.domain.shared.CustomerId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * dev / test 용 — 알림을 로그로만 출력. 실 운영에서는 EmailCustomerNotifier 또는
 * WebhookCustomerNotifier (Resilience4j 적용) 로 교체.
 */
@Component
@Profile("!prod")
public class LoggingCustomerNotifier implements CustomerNotifier {

    private static final Logger log = LoggerFactory.getLogger(LoggingCustomerNotifier.class);

    @Override
    public void notify(CustomerId customerId, NotificationType type,
                       Map<String, Object> context) {
        log.info("[notification] customer={} type={} context={}",
                customerId.value(), type, context);
    }
}
