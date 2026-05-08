-- prod (Postgres) 전용 — V12 의 일반 인덱스를 partial index 로 교체.
--
-- 활성 grace 가 있는 endpoint (previous_secret IS NOT NULL) 는 보통 전체의 < 1% 라
-- partial index 가 작고 빠름. cleanup 배치의 "만료 grace 찾기" 쿼리에 최적.
--
-- 일반 인덱스를 그대로 두면 cleanup 쿼리가 인덱스 풀스캔을 함 (대다수 row 의 valid_until 은
-- NULL 이라 의미 없는 lookup). partial 로 *작은 활성 set* 만 인덱싱.

DROP INDEX IF EXISTS idx_webhook_endpoint_grace_expiry;

CREATE INDEX idx_webhook_endpoint_grace_expiry
    ON webhook_endpoints (previous_secret_valid_until)
    WHERE previous_secret IS NOT NULL;
