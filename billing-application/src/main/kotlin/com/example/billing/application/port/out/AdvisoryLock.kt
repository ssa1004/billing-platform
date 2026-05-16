package com.example.billing.application.port.out

/**
 * 분산 환경에서 같은 작업이 동시 실행되지 않도록 하는 트랜잭션 단위 advisory lock.
 *
 * 구현 (Postgres): `pg_advisory_xact_lock(hashtext(key))` — 트랜잭션 종료 시 자동
 * 해제. lock 획득 실패는 wait (default 동작) 또는 try mode 로 분기.
 *
 * 주요 사용처:
 *  - 월별 정산 — 같은 customer × 같은 month 정산이 두 worker 에서 동시에 시작되지 않도록
 *  - 가격 정책 적용 — 같은 plan 에 대한 동시 변경 방지
 */
interface AdvisoryLock {

    /**
     * 트랜잭션이 끝날 때까지 lock 보유 (블로킹). 같은 키를 다른 트랜잭션이 잡고 있으면 대기.
     * 반드시 `@Transactional` 메서드 안에서 호출해야 한다.
     */
    fun lock(key: String)

    /**
     * 즉시 lock 시도. 이미 점유되어 있으면 false 반환 (대기 안 함).
     * @return true = lock 획득, false = 다른 트랜잭션이 보유 중
     */
    fun tryLock(key: String): Boolean
}
