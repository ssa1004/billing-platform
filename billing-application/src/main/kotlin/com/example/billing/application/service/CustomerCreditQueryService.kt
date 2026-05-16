package com.example.billing.application.service

import com.example.billing.application.port.`in`.CustomerCreditQueryUseCase
import com.example.billing.application.port.out.CreditRepository
import com.example.billing.domain.credit.Credit
import com.example.billing.domain.shared.CustomerId
import com.example.billing.domain.shared.Money
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.util.Currency

/**
 * 고객 단위 Credit 조회 (read model). 운영 화면 / 사용자 대시보드 / 만료 알림 등이 호출.
 *
 * 현재는 도메인 레포지토리에 직접 위임 (단순). 트래픽이 커지면 별도 read replica /
 * materialized view / Redis 캐시로 분리 가능 — 이 인터페이스가 그 경계.
 */
@Service
@Transactional(readOnly = true)
open class CustomerCreditQueryService(
    private val credits: CreditRepository,
    private val clock: Clock,
) : CustomerCreditQueryUseCase {

    override fun usableBalances(customerId: CustomerId): Map<Currency, Money> {
        val now = clock.instant()
        val sums = LinkedHashMap<Currency, Money>()
        for (c in credits.findUsable(customerId, now)) {
            sums.merge(c.currency, c.balance, Money::add)
        }
        return sums
    }

    override fun usableBalance(customerId: CustomerId, currency: Currency): Money {
        val now = clock.instant()
        var sum = Money.zero(currency)
        for (c in credits.findUsable(customerId, now)) {
            if (c.currency == currency) {
                sum = sum.add(c.balance)
            }
        }
        return sum
    }

    override fun findExpiringSoon(customerId: CustomerId, within: Duration): List<Credit> {
        val now = clock.instant()
        return credits.findExpiringSoon(customerId, now, now.plus(within))
    }

    override fun findAll(customerId: CustomerId, limit: Int): List<Credit> =
        credits.findAllByCustomer(customerId, limit)
}
