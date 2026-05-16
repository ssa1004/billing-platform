package com.example.billing.application.port.`in`

import com.example.billing.domain.credit.Credit
import com.example.billing.domain.shared.CustomerId
import com.example.billing.domain.shared.Money
import java.time.Duration
import java.util.Currency

interface CustomerCreditQueryUseCase {

    /**
     * 통화별 사용 가능 잔액 합계. 만료 / 비활성 제외, balance > 0 만.
     * 다중 통화 사용자 (KRW + USD 등) 를 위해 currency → Money map.
     */
    fun usableBalances(customerId: CustomerId): Map<Currency, Money>

    /** 단일 통화 사용 가능 잔액. 잔액 없으면 0. */
    fun usableBalance(customerId: CustomerId, currency: Currency): Money

    /** [[now, now + within]] 안에 만료될 ACTIVE Credit. 만료 임박 순. */
    fun findExpiringSoon(customerId: CustomerId, within: Duration): List<Credit>

    /** 고객의 모든 Credit (status 무관, 최근 발급 우선). 운영 / 화면 grid 용. */
    fun findAll(customerId: CustomerId, limit: Int): List<Credit>
}
