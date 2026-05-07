-- Credit (선불/프로모 잔액) 도메인.
-- Wallet 과 분리한 이유: 발급 / 만료 / 회수 라이프사이클과 회계 처리가 거래 잔액과 다름.

CREATE TABLE credits (
    id                  UUID            PRIMARY KEY,
    customer_id         VARCHAR(64)     NOT NULL,
    type                VARCHAR(32)     NOT NULL,    -- PROMO / PREPAID / COMPENSATION / REFUND_TO_CREDIT
    currency            VARCHAR(3)      NOT NULL,
    granted_amount      DECIMAL(18, 2)  NOT NULL,
    balance             DECIMAL(18, 2)  NOT NULL,
    valid_from          TIMESTAMP       NOT NULL,
    valid_until         TIMESTAMP,                   -- null 이면 만료 없음
    status              VARCHAR(16)     NOT NULL,    -- ACTIVE / EXHAUSTED / EXPIRED / REVOKED
    reason              VARCHAR(256),
    created_at          TIMESTAMP       NOT NULL,
    updated_at          TIMESTAMP       NOT NULL,
    version             BIGINT          NOT NULL DEFAULT 0,
    CONSTRAINT chk_credit_balance_nonneg     CHECK (balance >= 0),
    CONSTRAINT chk_credit_balance_le_granted CHECK (balance <= granted_amount),
    CONSTRAINT chk_credit_validity_order     CHECK (valid_until IS NULL OR valid_until > valid_from)
);

-- 고객 단위 ACTIVE 잔액 조회용 (invoice 에 적용할 때 자주 호출)
CREATE INDEX idx_credits_customer_status
    ON credits (customer_id, status);

-- 만료 batch 가 valid_until 이 지난 ACTIVE 만 빠르게 스캔하기 위함
CREATE INDEX idx_credits_expiry_scan
    ON credits (status, valid_until);
