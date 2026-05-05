package com.example.billing.adapter.out.lock;

import com.example.billing.application.port.out.AdvisoryLock;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Postgres advisory lock 구현 — {@code pg_advisory_xact_lock} 사용.
 *
 * <p>트랜잭션 종료 시 자동 해제. 같은 트랜잭션에서 같은 키를 두 번 lock 해도 idempotent.</p>
 *
 * <p>{@code hashtext()} 으로 키를 64bit 정수로 해시. 이론적으로 collision 가능하지만 키 공간이
 * 클 때만 (1억+ 키) 실용적 위험. billing 도메인의 키 (settlement:cust:202605 등) 는 collision
 * 위험 무시 가능.</p>
 */
@Component
@Profile("!test-without-postgres")
public class PostgresAdvisoryLock implements AdvisoryLock {

    @PersistenceContext
    private EntityManager em;

    @Override
    public void lock(String key) {
        em.createNativeQuery("SELECT pg_advisory_xact_lock(hashtext(:k))")
                .setParameter("k", key)
                .getSingleResult();
    }

    @Override
    public boolean tryLock(String key) {
        Object result = em.createNativeQuery("SELECT pg_try_advisory_xact_lock(hashtext(:k))")
                .setParameter("k", key)
                .getSingleResult();
        return Boolean.TRUE.equals(result);
    }
}
