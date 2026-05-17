package com.example.billing.adapter.out.lock

import com.example.billing.application.port.out.AdvisoryLock
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

/**
 * Postgres advisory lock (이름 붙인 임의의 잠금, 실제 row 가 없어도 임의의 키에 lock 가능)
 * 구현 — `pg_advisory_xact_lock` 사용.
 *
 * `_xact_` 변종이라 트랜잭션이 끝나면 자동으로 해제됩니다. 같은 트랜잭션에서 같은
 * 키를 두 번 lock 해도 멱등성 (반복 호출해도 결과 동일) 이 보장됩니다.
 *
 * `hashtext()` 로 문자열 키를 64비트 정수로 해시합니다. 이론적으로 충돌 가능하지만
 * 키 공간이 매우 클 때만 (1억 개 이상) 실용적 위험. billing 도메인의 키 (예:
 * settlement:cust:202605) 는 충돌 위험을 무시 가능.
 */
@Component
@Profile("!test-without-postgres")
class PostgresAdvisoryLock : AdvisoryLock {

    @PersistenceContext
    private lateinit var em: EntityManager

    override fun lock(key: String) {
        em.createNativeQuery("SELECT pg_advisory_xact_lock(hashtext(:k))")
            .setParameter("k", key)
            .singleResult
    }

    override fun tryLock(key: String): Boolean {
        val result = em.createNativeQuery("SELECT pg_try_advisory_xact_lock(hashtext(:k))")
            .setParameter("k", key)
            .singleResult
        return result == true
    }
}
