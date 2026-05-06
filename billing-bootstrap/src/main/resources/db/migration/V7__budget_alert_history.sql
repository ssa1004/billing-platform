-- BudgetAlertRule 트리거 이력 (append-only).
-- BudgetAlertRule 자체는 cooldown 용 lastTriggeredAt 만 보관 — 분석/대시보드는 이 테이블 사용.

CREATE TABLE budget_alert_history (
    id                                  UUID            PRIMARY KEY,
    rule_id                             UUID            NOT NULL,
    customer_id                         VARCHAR(64)     NOT NULL,
    threshold_amount_at_trigger         DECIMAL(18, 2)  NOT NULL,
    projected_cost_at_trigger           DECIMAL(18, 2)  NOT NULL,
    currency                            VARCHAR(3)      NOT NULL,
    overshoot_ratio                     DOUBLE PRECISION NOT NULL,
    period_year_month                   VARCHAR(7)      NOT NULL,
    period_progress_ratio_at_trigger    DOUBLE PRECISION NOT NULL,
    occurred_at                         TIMESTAMP       NOT NULL,
    CONSTRAINT chk_history_overshoot_ge_one
        CHECK (overshoot_ratio >= 1.0),
    CONSTRAINT chk_history_progress_in_range
        CHECK (period_progress_ratio_at_trigger BETWEEN 0.0 AND 1.0)
);

-- rule 별 timeline 조회
CREATE INDEX idx_budget_history_rule_time
    ON budget_alert_history (rule_id, occurred_at DESC);

-- customer 별 통합 timeline
CREATE INDEX idx_budget_history_customer_time
    ON budget_alert_history (customer_id, occurred_at DESC);
