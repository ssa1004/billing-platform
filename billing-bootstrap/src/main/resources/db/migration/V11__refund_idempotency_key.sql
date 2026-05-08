-- Refund 테이블에 idempotency_key 추가.
-- 3-phase 환불 흐름의 PG-failure reconciler 가 stuck REQUESTED Refund 를 발견했을 때
-- 같은 키로 PG lookup 해서 실제 결과를 다시 끌어오기 위한 키. RefundCommand 가 받는 키를
-- 그대로 보관 (Payment 의 idempotency_key 와 같은 의미 — PG side 멱등성 키).
--
-- backfill 정책: nullable 로 추가 → 기존 row 는 null 로 둠 (재구동 시점에 신규 row 만 보장).
-- 운영 데이터에 NOT NULL 강제는 backfill 이후 추후 migration 으로.

ALTER TABLE refunds
    ADD COLUMN IF NOT EXISTS idempotency_key VARCHAR(128);

-- 같은 키로 Refund 가 중복 생성되지 않도록 unique index. nullable column 의 unique 는
-- Postgres / H2 모두 NULL 을 unique constraint 에서 제외 (backfill 안 된 옛날 row 가
-- 여러 개 있어도 통과).
CREATE UNIQUE INDEX IF NOT EXISTS uq_refund_idempotency_key
    ON refunds (idempotency_key);

-- Reconciler 가 호출할 핵심 인덱스 — REQUESTED 상태로 stuck 된 row 를 시간 오래된 순으로.
CREATE INDEX IF NOT EXISTS idx_refund_status_requested_at
    ON refunds (status, requested_at);

-- Payments 도 같은 패턴 — PENDING 으로 오래된 row 를 reconciler 가 스캔.
-- 기존 idx_payments_status (status 단일 컬럼) 는 PENDING/APPROVED/FAILED 비율 차이가
-- 커서 PENDING 만 골라낼 때 selectivity 가 떨어짐. (status, created_at) composite 로
-- 생성 시각 정렬까지 인덱스에서 처리.
CREATE INDEX IF NOT EXISTS idx_payments_status_created_at
    ON payments (status, created_at);
