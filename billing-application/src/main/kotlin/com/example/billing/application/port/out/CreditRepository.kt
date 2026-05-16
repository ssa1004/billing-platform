package com.example.billing.application.port.out

import com.example.billing.domain.credit.Credit
import com.example.billing.domain.credit.CreditId
import com.example.billing.domain.shared.CustomerId
import java.time.Instant
import java.util.Optional

interface CreditRepository {

    fun save(credit: Credit)

    fun findById(id: CreditId): Optional<Credit>

    /**
     * 차감에 사용 가능한 ACTIVE Credit 들. 만료 임박 → 발급 시점 빠른 순.
     * application service 가 위에서부터 차례로 차감.
     */
    fun findUsable(customerId: CustomerId, now: Instant): List<Credit>

    /**
     * 만료 batch 가 호출. `valid_until <= now` 인 ACTIVE 들. [limit] 까지만.
     * batch 가 결과 0 이 될 때까지 반복 호출.
     */
    fun findExpiredCandidates(now: Instant, limit: Int): List<Credit>

    /**
     * 운영 화면 / 알림 — `[now, now + within]` 안에 만료될 ACTIVE 들. 만료 임박 순.
     * 만료 없는 (validUntil = null) 건은 제외.
     */
    fun findExpiringSoon(customerId: CustomerId, now: Instant, until: Instant): List<Credit>

    /** 고객의 모든 Credit (status 무관). 화면 grid 용. */
    fun findAllByCustomer(customerId: CustomerId, limit: Int): List<Credit>
}
