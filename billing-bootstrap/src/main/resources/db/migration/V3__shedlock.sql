-- ShedLock — @Scheduled 메서드의 분산 lock 보관 테이블.
--
-- multi-instance 환경에서 같은 schedule 이 여러 Pod 에서 동시 실행되지 않도록 한다.
-- 각 schedule (= name) 은 한 시점에 하나의 인스턴스만 락을 잡고 실행한다.
CREATE TABLE shedlock (
    name        VARCHAR(64)  NOT NULL,
    lock_until  TIMESTAMP WITH TIME ZONE NOT NULL,
    locked_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    locked_by   VARCHAR(255) NOT NULL,
    PRIMARY KEY (name)
);
