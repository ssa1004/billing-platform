package com.example.billing.adapter.out.lock

import com.example.billing.application.port.out.AdvisoryLock
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

/**
 * dev / 단위테스트용 — H2 는 advisory lock 미지원이라 항상 lock 획득으로 처리.
 *
 * 운영 (Postgres) 에서는 [PostgresAdvisoryLock] 이 활성화된다.
 */
@Component
@Profile("test-without-postgres")
class NoOpAdvisoryLock : AdvisoryLock {

    override fun lock(key: String) {
        // no-op
    }

    override fun tryLock(key: String): Boolean = true
}
