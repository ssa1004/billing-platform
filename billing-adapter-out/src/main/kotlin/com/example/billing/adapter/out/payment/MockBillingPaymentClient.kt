package com.example.billing.adapter.out.payment

import com.example.billing.application.port.out.PaymentClient
import com.example.billing.application.port.out.PaymentClient.PaymentFatalException
import com.example.billing.domain.invoice.Invoice
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

/**
 * dev / test 용 PaymentClient. 운영 구현은 별도 (Resilience4j + 실제 PG).
 *
 * customerId 가 "FAIL_*" 로 시작하면 실패, "FATAL_*" 로 시작하면 영구 실패. 그 외는 모두 승인.
 */
@Component
@Profile("!prod")
class MockBillingPaymentClient : PaymentClient {

    override fun charge(invoice: Invoice): Boolean {
        val customerId = invoice.customerId.value
        log.info(
            "[mock] charge invoice={} customer={} amount={}",
            invoice.id, customerId, invoice.total,
        )
        if (customerId.startsWith("FATAL_")) {
            throw PaymentFatalException("mock fatal payment failure for $customerId")
        }
        if (customerId.startsWith("FAIL_")) {
            return false
        }
        return true
    }

    companion object {
        private val log = LoggerFactory.getLogger(MockBillingPaymentClient::class.java)
    }
}
