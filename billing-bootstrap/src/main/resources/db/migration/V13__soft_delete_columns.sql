-- 회계/빌링 도메인 (invoices / payments / refunds) 의 soft delete 지원 (ADR-0030).
--
-- 회계 / 결제 도메인의 *철칙*: 한 번 INSERT 된 row 는 *물리* 삭제하지 않습니다. 사용자 / 운영자
-- 가 "삭제" 를 요청하면 deleted_at 으로 마킹만 하고 row 자체는 그대로 둡니다.
--
-- 이유:
--   1) 회계 감사 — "삭제된 invoice 한 행이 그 달 결산에 들어가 있었다면?" 같은 질문에 *몇 년 뒤*
--      답할 수 있어야 함. 물리 삭제 후에는 "원래 그 행이 있었는지" 자체를 증명 못 함.
--   2) PG / 정산 추적 — Payment / Refund 는 외부 PG (결제 게이트웨이) 에 매칭되는 row.
--      "DB 에서 사라졌는데 PG 에는 남아있는" 정합 깨짐 사고를 부름.
--   3) 운영 분쟁 — "내가 환불 안 했는데 처리됐다" 같은 customer 컴플레인. 물리 삭제하면
--      *지운 행위* 자체를 audit 로 못 남김 — 누가 지웠는지조차 추적 불가.
--
-- 컬럼 의미:
--   deleted_at   — NULL 이면 활성, 값이 있으면 *논리 삭제됨*. 컬럼 검색 selectivity 가 낮아
--                  대부분의 read query 는 "deleted_at IS NULL" 필터를 항상 같이 검 (인덱스도
--                  부분 인덱스로 변환 가능 — V13_1 partial index 참조).
--   deleted_by   — 누가 삭제했나. user / operator id. SOFT_DELETED audit 와 짝.

-- ────────────────────────────────────────────────────────
-- INVOICES — 청구서 soft delete
-- ────────────────────────────────────────────────────────
-- 컬럼은 한 줄에 하나씩 ADD — 멀티-ADD 문법은 H2 가 거부하므로 (Postgres 는 둘 다 허용).
ALTER TABLE invoices
    ADD COLUMN deleted_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE invoices
    ADD COLUMN deleted_by VARCHAR(128);

-- 활성 row 의 lookup 효율을 위한 인덱스. 대부분의 read 가 deleted_at IS NULL 을 함께 검.
-- H2 / Postgres 모두 호환되는 *full* 인덱스. Partial index (WHERE deleted_at IS NULL) 가
-- 더 작지만 H2 미지원이라 prod 전용 migration (V13_1) 에서 별도 처리.
CREATE INDEX idx_invoices_deleted_at ON invoices (deleted_at);

-- invariant: deleted_at 과 deleted_by 는 항상 짝. 한쪽만 set 인 row 는 운영 사고 신호.
ALTER TABLE invoices
    ADD CONSTRAINT chk_invoices_soft_delete_pair
        CHECK (
            (deleted_at IS NULL AND deleted_by IS NULL)
         OR (deleted_at IS NOT NULL AND deleted_by IS NOT NULL)
        );

-- ────────────────────────────────────────────────────────
-- PAYMENTS — 결제 soft delete (PG 매칭 row 라 물리 삭제 절대 금지)
-- ────────────────────────────────────────────────────────
ALTER TABLE payments
    ADD COLUMN deleted_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE payments
    ADD COLUMN deleted_by VARCHAR(128);

CREATE INDEX idx_payments_deleted_at ON payments (deleted_at);

ALTER TABLE payments
    ADD CONSTRAINT chk_payments_soft_delete_pair
        CHECK (
            (deleted_at IS NULL AND deleted_by IS NULL)
         OR (deleted_at IS NOT NULL AND deleted_by IS NOT NULL)
        );

-- ────────────────────────────────────────────────────────
-- REFUNDS — 환불 soft delete
-- ────────────────────────────────────────────────────────
ALTER TABLE refunds
    ADD COLUMN deleted_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE refunds
    ADD COLUMN deleted_by VARCHAR(128);

CREATE INDEX idx_refunds_deleted_at ON refunds (deleted_at);

ALTER TABLE refunds
    ADD CONSTRAINT chk_refunds_soft_delete_pair
        CHECK (
            (deleted_at IS NULL AND deleted_by IS NULL)
         OR (deleted_at IS NOT NULL AND deleted_by IS NOT NULL)
        );
