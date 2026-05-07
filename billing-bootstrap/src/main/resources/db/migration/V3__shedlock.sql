-- ShedLock — @Scheduled 메서드의 분산 lock 보관 테이블.
--
-- 인스턴스가 여러 개일 때 같은 schedule 이 여러 Pod 에서 동시에 실행되지 않게 막아줍니다.
-- 각 schedule (= name) 은 한 시점에 한 인스턴스만 lock 을 잡고 실행합니다.
CREATE TABLE shedlock (
    name        VARCHAR(64)  NOT NULL,
    lock_until  TIMESTAMP WITH TIME ZONE NOT NULL,
    locked_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    locked_by   VARCHAR(255) NOT NULL,
    PRIMARY KEY (name)
);
