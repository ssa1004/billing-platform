package com.example.billing.adapter.out.pg;

import com.example.billing.application.port.out.PgClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 로컬 dev 용 Mock PG. wallet.pg.enabled=false 일 때 활성. 항상 승인.
 * 실패 시나리오 테스트는 idempotencyKey 가 "FAIL_" 로 시작하면 reject.
 */
@Component
@ConditionalOnProperty(name = "wallet.pg.enabled", havingValue = "false", matchIfMissing = true)
@Slf4j
public class MockPgClient implements PgClient {

    @Override
    public AuthorizeResult authorize(AuthorizeRequest req) {
        if (req.idempotencyKey().startsWith("FAIL_")) {
            log.info("[mock-pg] simulating failure for {}", req.idempotencyKey());
            return AuthorizeResult.rejected("MOCK_FAIL", "simulated failure");
        }
        String pgTxId = "mock-pg-" + UUID.randomUUID();
        log.info("[mock-pg] approved {} → {}", req.idempotencyKey(), pgTxId);
        return AuthorizeResult.approved(pgTxId);
    }

    @Override
    public RefundResult refund(RefundRequest req) {
        String pgRefundId = "mock-refund-" + UUID.randomUUID();
        log.info("[mock-pg] refunded {} → {}", req.pgTransactionId(), pgRefundId);
        return RefundResult.approved(pgRefundId);
    }
}
