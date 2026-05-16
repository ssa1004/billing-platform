package com.example.billing.application.service

import com.example.billing.application.port.`in`.ExpireCreditsUseCase
import com.example.billing.application.port.out.CreditRepository
import com.example.billing.application.port.out.EventPublisher
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock

/**
 * Credit 만료 처리. `valid_until <= now` 인 ACTIVE Credit 들을 EXPIRED 로 전이.
 *
 * 한 트랜잭션에 한 batch 단위만 처리 — 전체를 한 트랜잭션으로 묶으면 큰 row set 에서
 * lock contention 과 long-running transaction 문제. 호출자 (Spring Batch tasklet 등) 가
 * 결과 0 이 될 때까지 반복.
 *
 * **낙관적 락 자동 재시도**: 같은 Credit 을 동시에 차감하는 결제 (ApplyCreditService)
 * 가 도는 동안 만료 batch 가 돌면 `@Version` 충돌 발생 가능. 만료 처리는 *멱등*
 * (이미 EXPIRED 면 [com.example.billing.domain.credit.Credit.expire] 가 null 을 돌려 skip) 이라 재시도 안전.
 * 충돌 budget 을 넘기면 그대로 throw — 다음 batch run 에서 다시 시도하면 됨.
 */
@Service
open class ExpireCreditsService(
    private val credits: CreditRepository,
    private val events: EventPublisher,
    private val clock: Clock,
    txManager: PlatformTransactionManager,
) : ExpireCreditsUseCase {

    private val tx = TransactionTemplate(txManager)

    override fun expireBatch(limit: Int): Int =
        OptimisticLockRetry.withRetry(MAX_RETRY_ATTEMPTS, RETRY_BACKOFF_MILLIS) {
            tx.execute { doExpireBatch(limit) } ?: 0
        }

    private fun doExpireBatch(limit: Int): Int {
        val now = clock.instant()
        val candidates = credits.findExpiredCandidates(now, limit)
        if (candidates.isEmpty()) return 0

        var processed = 0
        for (credit in candidates) {
            val event = credit.expire(clock) ?: continue // 이미 종착 (race)
            credits.save(credit)
            events.publish(event)
            processed++
        }
        log.info("expired credits asOf={} processed={}/{}", now, processed, candidates.size)
        return processed
    }

    companion object {
        private val log = LoggerFactory.getLogger(ExpireCreditsService::class.java)
        private const val MAX_RETRY_ATTEMPTS = 3
        private const val RETRY_BACKOFF_MILLIS = 50L
    }
}
