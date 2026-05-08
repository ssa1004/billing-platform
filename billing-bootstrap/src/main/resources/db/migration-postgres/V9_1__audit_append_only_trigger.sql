-- Audit log 의 append-only invariant 를 DB 레벨에서 추가로 강제.
-- (Postgres 전용 — H2 와 trigger 문법이 달라 application.yml 의 prod 프로필에서만 적용.)
--
-- 도메인 객체 / JPA listener (AuditAppendOnlyGuard) 가 1차 / 2차 방어선이지만,
-- EntityManager.createNativeQuery 로 직접 SQL UPDATE / DELETE 를 보내면 listener 도 우회됩니다.
-- DB trigger 가 마지막 방어선 — 어떤 경로로 들어와도 audit row 는 수정 / 삭제 불가.
--
-- 정정이 필요하면 *새 row* 를 INSERT 하세요. 그래야 timeline 이 그대로 보존됩니다.

CREATE OR REPLACE FUNCTION audit_entries_block_modification()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'audit_entries is append-only — % is forbidden. 정정이 필요하면 새 row 를 INSERT 하세요.', TG_OP
        USING ERRCODE = 'check_violation';
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS audit_entries_no_update ON audit_entries;
DROP TRIGGER IF EXISTS audit_entries_no_delete ON audit_entries;

CREATE TRIGGER audit_entries_no_update
    BEFORE UPDATE ON audit_entries
    FOR EACH ROW EXECUTE FUNCTION audit_entries_block_modification();

CREATE TRIGGER audit_entries_no_delete
    BEFORE DELETE ON audit_entries
    FOR EACH ROW EXECUTE FUNCTION audit_entries_block_modification();
