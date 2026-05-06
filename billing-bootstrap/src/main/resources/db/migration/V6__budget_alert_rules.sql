-- BudgetAlertRule — 월말 예상 청구액이 임계를 넘으면 알림 발송 규칙.

CREATE TABLE budget_alert_rules (
    id                  UUID            PRIMARY KEY,
    customer_id         VARCHAR(64)     NOT NULL,
    threshold_amount    DECIMAL(18, 2)  NOT NULL,
    currency            VARCHAR(3)      NOT NULL,
    cooldown_seconds    BIGINT          NOT NULL,
    status              VARCHAR(16)     NOT NULL,        -- ACTIVE / PAUSED
    last_evaluated_at   TIMESTAMP,
    last_triggered_at   TIMESTAMP,
    created_at          TIMESTAMP       NOT NULL,
    updated_at          TIMESTAMP       NOT NULL,
    version             BIGINT          NOT NULL DEFAULT 0,
    CONSTRAINT chk_budget_threshold_positive CHECK (threshold_amount > 0),
    CONSTRAINT chk_budget_cooldown_positive  CHECK (cooldown_seconds > 0)
);

-- Evaluate batch 가 ACTIVE 만 골라 customer 단위로 묶어 처리
CREATE INDEX idx_budget_status_customer
    ON budget_alert_rules (status, customer_id);
