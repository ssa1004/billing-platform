-- Webhook secret rotation 의 grace window 지원 (ADR-0029).
--
-- 운영 표준 (Stripe / GitHub / 토스페이먼츠) 의 secret rotation:
--   1. 새 secret 을 생성, 현재 secret 으로 활성.
--   2. 이전 secret 은 24h grace 동안 *함께 유효* — 발신 측은 두 secret 으로 각각 서명한 두 값을
--      헤더에 같이 보냄 → customer 가 어느 한 쪽이라도 일치하면 검증 통과.
--   3. 24h 후 previousSecret 은 자동 만료 — 그 시점에 customer 는 새 secret 으로 업데이트되어 있어야 함.
--
-- 이전 정책 (즉시 invalidate) 의 단점: customer 가 새 secret 을 반영할 짧은 시간 동안 모든 webhook
-- 이 검증 실패로 떨어짐. grace 가 deployment overlap 을 자연스럽게 흡수.

ALTER TABLE webhook_endpoints
    ADD COLUMN previous_secret              VARCHAR(128),
    ADD COLUMN previous_secret_valid_until  TIMESTAMP;

-- invariant: previous_secret 과 previous_secret_valid_until 은 항상 짝.
-- (둘 다 NULL 이거나 둘 다 set — 한쪽만 set 인 row 는 운영 사고 신호.)
ALTER TABLE webhook_endpoints
    ADD CONSTRAINT chk_webhook_endpoint_previous_secret_pair
        CHECK (
            (previous_secret IS NULL AND previous_secret_valid_until IS NULL)
         OR (previous_secret IS NOT NULL AND previous_secret_valid_until IS NOT NULL)
        );

-- 일반 (full) 인덱스 — H2 / Postgres 호환. previousSecret cleanup 배치가 만료된 row 를 빠르게
-- 찾을 때 사용. partial index 가 더 작지만 H2 미지원이라 prod 전용 migration 으로 분리.
CREATE INDEX idx_webhook_endpoint_grace_expiry
    ON webhook_endpoints (previous_secret_valid_until);
