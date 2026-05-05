package com.example.billing.adapter.out.lock;

import com.example.billing.application.port.out.AdvisoryLock;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * dev / 단위테스트용 — H2 는 advisory lock 미지원이라 항상 lock 획득으로 처리.
 *
 * <p>운영 (Postgres) 에서는 {@link PostgresAdvisoryLock} 이 활성화된다.</p>
 */
@Component
@Profile("test-without-postgres")
public class NoOpAdvisoryLock implements AdvisoryLock {

    @Override public void lock(String key) {
        // no-op
    }

    @Override public boolean tryLock(String key) {
        return true;
    }
}
