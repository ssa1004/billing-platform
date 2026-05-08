-- Postgres 전용 partial index — 활성 row (deleted_at IS NULL) 만 인덱싱.
-- 운영 환경에서는 활성 row 가 99%, 삭제 row 가 1% 미만이라 partial index 가 훨씬 작고
-- read query 의 hot path 를 바로 cover.
--
-- H2 는 partial index 미지원이라 공통 migration (V13) 에서는 *full* 인덱스를 만들었고,
-- prod (postgres) 에서는 그것을 drop 후 partial index 로 교체합니다.

DROP INDEX IF EXISTS idx_invoices_deleted_at;
CREATE INDEX idx_invoices_active
    ON invoices (id)
    WHERE deleted_at IS NULL;

DROP INDEX IF EXISTS idx_payments_deleted_at;
CREATE INDEX idx_payments_active
    ON payments (id)
    WHERE deleted_at IS NULL;

DROP INDEX IF EXISTS idx_refunds_deleted_at;
CREATE INDEX idx_refunds_active
    ON refunds (id)
    WHERE deleted_at IS NULL;
