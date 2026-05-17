package com.example.billing.adapter.out.pg

import com.example.billing.application.port.out.PgClient
import com.example.billing.application.port.out.PgClient.AuthorizeRequest
import com.example.billing.application.port.out.PgClient.AuthorizeResult
import com.example.billing.application.port.out.PgClient.LookupResult
import com.example.billing.application.port.out.PgClient.RefundRequest
import com.example.billing.application.port.out.PgClient.RefundResult
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * 로컬 dev 용 Mock PG. billing.pg.enabled=false 일 때 활성. 항상 승인.
 * 실패 시나리오 테스트는 idempotencyKey 가 "FAIL_" 로 시작하면 reject.
 */
@Component
@ConditionalOnProperty(name = ["billing.pg.enabled"], havingValue = "false", matchIfMissing = true)
class MockPgClient : PgClient {

    override fun authorize(req: AuthorizeRequest): AuthorizeResult {
        if (req.idempotencyKey.startsWith("FAIL_")) {
            log.info("[mock-pg] simulating failure for {}", req.idempotencyKey)
            return AuthorizeResult.rejected("MOCK_FAIL", "simulated failure")
        }
        val pgTxId = "mock-pg-" + UUID.randomUUID()
        log.info("[mock-pg] approved {} → {}", req.idempotencyKey, pgTxId)
        return AuthorizeResult.approved(pgTxId)
    }

    override fun refund(req: RefundRequest): RefundResult {
        val pgRefundId = "mock-refund-" + UUID.randomUUID()
        log.info("[mock-pg] refunded {} → {}", req.pgTransactionId, pgRefundId)
        return RefundResult.approved(pgRefundId)
    }

    override fun lookup(idempotencyKey: String): LookupResult {
        // Mock 은 PG 결과를 영속 보관하지 않으므로 항상 NOT_FOUND. 운영 PG 는 실제 조회.
        // dev 환경에서 reconciler 가 도는 게 의미 없는 환경 — billing.pg.reconciler.enabled=false 로
        // 끄거나, 통합 테스트 시 별도 Mock 으로 교체.
        log.debug("[mock-pg] lookup {} → NOT_FOUND", idempotencyKey)
        return LookupResult.notFound()
    }

    companion object {
        private val log = LoggerFactory.getLogger(MockPgClient::class.java)
    }
}
