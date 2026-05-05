-- 사용량 기반 청구 (B2B SaaS billing) 테이블.
-- V1 의 wallet/payment/order/refund/ledger/outbox 위에 추가되는 영역.

-- ── 사용량 이벤트 ──
CREATE TABLE usage_events (
    event_id        UUID         PRIMARY KEY,
    customer_id     VARCHAR(64)  NOT NULL,
    resource_type   VARCHAR(32)  NOT NULL,
    quantity        BIGINT       NOT NULL,
    occurred_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    received_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT chk_usage_quantity_nonneg CHECK (quantity >= 0)
);

CREATE INDEX idx_usage_customer_resource_occurred
    ON usage_events (customer_id, resource_type, occurred_at);
CREATE INDEX idx_usage_received_at
    ON usage_events (received_at);

-- ── 집계 결과 (시간/일/월 단위. 현재는 월만 사용) ──
CREATE TABLE aggregated_usage (
    id                  UUID         PRIMARY KEY,
    customer_id         VARCHAR(64)  NOT NULL,
    resource_type       VARCHAR(32)  NOT NULL,
    period_year_month   VARCHAR(7)   NOT NULL,
    total_quantity      BIGINT       NOT NULL,
    event_count         BIGINT       NOT NULL,
    aggregated_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_aggregated_customer_resource_period
        UNIQUE (customer_id, resource_type, period_year_month)
);

CREATE INDEX idx_aggregated_period
    ON aggregated_usage (period_year_month);

-- ── 가격 정책 ──
CREATE TABLE pricing_plans (
    id                UUID         PRIMARY KEY,
    customer_id       VARCHAR(64),  -- null = default plan
    name              VARCHAR(64)  NOT NULL,
    tiers_json        TEXT         NOT NULL,   -- 운영(PG)에선 jsonb
    effective_from    TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_pricing_customer_effective
    ON pricing_plans (customer_id, effective_from);

-- ── 청구서 ──
CREATE TABLE invoices (
    id                       UUID         PRIMARY KEY,
    customer_id              VARCHAR(64)  NOT NULL,
    period_year_month        VARCHAR(7)   NOT NULL,
    total_amount             NUMERIC(19,2) NOT NULL,
    currency_code            VARCHAR(3)   NOT NULL,
    status                   VARCHAR(20)  NOT NULL,
    lines_json               TEXT         NOT NULL,
    pricing_snapshot_json    TEXT         NOT NULL,
    created_at               TIMESTAMP WITH TIME ZONE NOT NULL,
    issued_at                TIMESTAMP WITH TIME ZONE,
    due_at                   TIMESTAMP WITH TIME ZONE,
    paid_at                  TIMESTAMP WITH TIME ZONE,
    version                  BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uq_invoice_customer_period UNIQUE (customer_id, period_year_month)
);

CREATE INDEX idx_invoice_status_due ON invoices (status, due_at);
CREATE INDEX idx_invoice_customer ON invoices (customer_id);

-- ── 정산 실행 (audit + worker pool 작업 큐) ──
CREATE TABLE settlement_runs (
    id                    UUID         PRIMARY KEY,
    period_year_month     VARCHAR(7)   NOT NULL,
    customer_id           VARCHAR(64),  -- null = aggregate row
    status                VARCHAR(20)  NOT NULL,
    started_at            TIMESTAMP WITH TIME ZONE,
    finished_at           TIMESTAMP WITH TIME ZONE,
    invoices_generated    INT          NOT NULL DEFAULT 0,
    payments_attempted    INT          NOT NULL DEFAULT 0,
    payments_succeeded    INT          NOT NULL DEFAULT 0,
    failure_reason        VARCHAR(1024),
    created_at            TIMESTAMP WITH TIME ZONE NOT NULL,
    version               BIGINT       NOT NULL DEFAULT 0
);

CREATE INDEX idx_settlement_period_status
    ON settlement_runs (period_year_month, status);
CREATE INDEX idx_settlement_customer_period
    ON settlement_runs (customer_id, period_year_month);
