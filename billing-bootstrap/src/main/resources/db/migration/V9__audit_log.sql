-- 감사 로그 (append-only, 한 번 적으면 수정/삭제 안 함, 추가만)
-- — 회계 감사 (SOX, 미국 상장 기업 회계 책임법) / 카드 정보 보안 표준 (PCI-DSS) /
--   운영 분쟁 대응의 1차 근거 자료.
-- 한 번 INSERT 된 row 는 *절대* UPDATE / DELETE 하지 않습니다. 정정도 새 row 로.

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

-- 가장 흔한 쿼리 — 특정 객체의 변경 timeline ("이 invoice 에 무슨 일이 있었나")
CREATE INDEX idx_audit_target_time
    ON audit_entries (target_type, target_id, occurred_at DESC);

-- 운영자 활동 추적 — "이 운영자가 오늘 한 행위들"
CREATE INDEX idx_audit_actor_time
    ON audit_entries (actor_type, actor_id, occurred_at DESC);

-- 분산 추적 join — 같은 traceId (한 요청의 모든 단계가 공유) 에 묶인 audit 한 번에 조회
CREATE INDEX idx_audit_trace
    ON audit_entries (trace_id);

-- 시간 구간 전체 스캔 (정기 정합성 검증 / SIEM 연동, SIEM = 보안 이벤트 모아서 분석/알림)
CREATE INDEX idx_audit_action_time
    ON audit_entries (action, occurred_at DESC);
