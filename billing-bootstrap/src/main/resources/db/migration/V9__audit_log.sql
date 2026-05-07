-- 감사 로그 (append-only) — SOX / PCI-DSS / 운영 분쟁 대응의 1차 근거.
-- 한 번 INSERT 된 row 는 *절대* UPDATE / DELETE 안 함. 정정도 새 row 로.

CREATE TABLE audit_entries (
    id              UUID            PRIMARY KEY,

    -- 행위 주체
    actor_type      VARCHAR(16)     NOT NULL,        -- USER / OPERATOR / SYSTEM / EXTERNAL
    actor_id        VARCHAR(128)    NOT NULL,        -- userId / operatorId / component name 등
    actor_ip        VARCHAR(64),                     -- HTTP 진입점일 때만
    actor_user_agent VARCHAR(512),                   -- HTTP 진입점일 때만

    -- 행위 분류
    action          VARCHAR(64)     NOT NULL,        -- AuditAction enum (이름)

    -- 대상 (어떤 도메인의 어떤 객체에 일어난 일인가)
    target_type     VARCHAR(64)     NOT NULL,        -- "Invoice" / "Payment" / "WebhookEndpoint" 등
    target_id       VARCHAR(128)    NOT NULL,        -- UUID 또는 자연 키 — string 으로 통일

    -- 변경 내용 (둘 다 nullable: null=생성/null=삭제)
    before_json     TEXT,
    after_json      TEXT,

    -- 부수 정보
    reason          VARCHAR(1024),                   -- 자유 텍스트 — "customer requested" 등
    trace_id        VARCHAR(64),                     -- 분산 추적 (같은 요청의 모든 audit 동일)
    occurred_at     TIMESTAMP       NOT NULL
);

-- 가장 흔한 query — 특정 객체의 변경 timeline ("이 invoice 에 무슨 일이 있었나")
CREATE INDEX idx_audit_target_time
    ON audit_entries (target_type, target_id, occurred_at DESC);

-- 운영자 활동 추적 — "이 운영자가 오늘 한 행위들"
CREATE INDEX idx_audit_actor_time
    ON audit_entries (actor_type, actor_id, occurred_at DESC);

-- 분산 추적 join — 한 traceId 의 audit 모두 한 번에
CREATE INDEX idx_audit_trace
    ON audit_entries (trace_id);

-- 시간 구간 전체 스캔 (정기 정합성 / SIEM 연동)
CREATE INDEX idx_audit_action_time
    ON audit_entries (action, occurred_at DESC);
