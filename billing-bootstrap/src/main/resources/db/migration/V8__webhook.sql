-- Webhook 전송 시스템.
-- customer 가 자기 서버 URL 을 endpoint 로 등록 → 우리 도메인 이벤트가 그 URL 로 HTTP POST.
-- (외부 PG 가 가맹점에게 결제 결과 알리는 방식의 우리 버전.)

-- ── Endpoint: customer 가 등록한 정보 ──
CREATE TABLE webhook_endpoints (
    id                          UUID            PRIMARY KEY,
    customer_id                 VARCHAR(64)     NOT NULL,
    url                         VARCHAR(2048)   NOT NULL,
    -- HMAC-SHA256 (비밀 키와 메시지로 만든 위조 방지 서명) 키. 256비트 (= 64자 hex).
    -- 평문은 endpoint 등록 응답에 단 한 번만 노출 (이후 조회 응답은 secret 미반환).
    -- DB 에는 매 request 서명에 사용해야 하므로 평문으로 보관 — 운영에서는 KMS / app-level
    -- envelope encryption 으로 한 단계 더 감싸는 것이 권장 (현재는 단순화).
    secret                      VARCHAR(128)    NOT NULL,
    -- 구독 이벤트 타입 목록을 JSON 배열로. 빈 배열이면 모든 이벤트 구독 (default).
    -- 여기서는 TEXT 로 저장 — 운영 PG 에서는 jsonb 로 바꿔도 됨.
    subscribed_event_types_json TEXT            NOT NULL,
    status                      VARCHAR(16)     NOT NULL,    -- ACTIVE / PAUSED
    created_at                  TIMESTAMP       NOT NULL,
    updated_at                  TIMESTAMP       NOT NULL,
    version                     BIGINT          NOT NULL DEFAULT 0
);

-- 한 customer 의 endpoint 들 조회
CREATE INDEX idx_webhook_endpoint_customer
    ON webhook_endpoints (customer_id);

-- 디스패처 (스케줄/등록 시점) — ACTIVE endpoint 들을 customer 별로
CREATE INDEX idx_webhook_endpoint_status_customer
    ON webhook_endpoints (status, customer_id);


-- ── Delivery: 한 이벤트의 한 endpoint 로의 전송 시도 기록 ──
CREATE TABLE webhook_deliveries (
    id                  UUID            PRIMARY KEY,
    endpoint_id         UUID            NOT NULL,
    event_type          VARCHAR(64)     NOT NULL,
    payload             TEXT            NOT NULL,
    status              VARCHAR(16)     NOT NULL,    -- PENDING / IN_FLIGHT / SUCCESS / DEAD_LETTERED
    attempt_count       INTEGER         NOT NULL,
    next_attempt_at     TIMESTAMP,                   -- PENDING 일 때만 의미
    last_response_status INTEGER,
    last_error          VARCHAR(256),
    created_at          TIMESTAMP       NOT NULL,
    updated_at          TIMESTAMP       NOT NULL,
    delivered_at        TIMESTAMP,
    version             BIGINT          NOT NULL DEFAULT 0,
    CONSTRAINT chk_webhook_delivery_attempt_nonneg CHECK (attempt_count >= 0),
    CONSTRAINT fk_webhook_delivery_endpoint
        FOREIGN KEY (endpoint_id) REFERENCES webhook_endpoints (id) ON DELETE CASCADE
);

-- Worker dispatch 인덱스. 워커가 매 분마다 다음 쿼리를 날립니다:
--   SELECT ... WHERE status='PENDING' AND next_attempt_at <= now()
--           ORDER BY next_attempt_at LIMIT N FOR UPDATE SKIP LOCKED
-- SKIP LOCKED (이미 잠긴 row 는 건너뛰는 옵션) 덕분에 여러 워커가 동시에 같은 쿼리를
-- 날려도 같은 row 를 두 번 잡지 않습니다.
CREATE INDEX idx_webhook_delivery_dispatch
    ON webhook_deliveries (status, next_attempt_at);

-- 운영 화면 — endpoint 별 timeline
CREATE INDEX idx_webhook_delivery_endpoint_time
    ON webhook_deliveries (endpoint_id, created_at DESC);

-- DEAD_LETTERED 만 모아서 보는 화면
CREATE INDEX idx_webhook_delivery_status_time
    ON webhook_deliveries (status, created_at DESC);
