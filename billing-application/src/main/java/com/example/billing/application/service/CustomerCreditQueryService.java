package com.example.billing.application.service;

import com.example.billing.application.port.in.CustomerCreditQueryUseCase;
import com.example.billing.application.port.out.CreditRepository;
import com.example.billing.domain.credit.Credit;
import com.example.billing.domain.shared.CustomerId;
import com.example.billing.domain.shared.Money;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 고객 단위 Credit 조회 (read model). 운영 화면 / 사용자 대시보드 / 만료 알림 등이 호출.
 *
 * <p>현재는 도메인 레포지토리에 직접 위임 (단순). 트래픽이 커지면 별도 read replica /
 * materialized view / Redis 캐시로 분리 가능 — 이 인터페이스가 그 경계.</p>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CustomerCreditQueryService implements CustomerCreditQueryUseCase {

    private final CreditRepository credits;
    private final Clock clock;

    @Override
    public Map<Currency, Money> usableBalances(CustomerId customerId) {
        Instant now = clock.instant();
        Map<Currency, Money> sums = new LinkedHashMap<>();
        for (Credit c : credits.findUsable(customerId, now)) {
            sums.merge(c.currency(), c.balance(), Money::add);
        }
        return sums;
    }

    @Override
    public Money usableBalance(CustomerId customerId, Currency currency) {
        Instant now = clock.instant();
        Money sum = Money.zero(currency);
        for (Credit c : credits.findUsable(customerId, now)) {
            if (c.currency().equals(currency)) {
                sum = sum.add(c.balance());
            }
        }
        return sum;
    }

    @Override
    public List<Credit> findExpiringSoon(CustomerId customerId, Duration within) {
        Instant now = clock.instant();
        return credits.findExpiringSoon(customerId, now, now.plus(within));
    }

    @Override
    public List<Credit> findAll(CustomerId customerId, int limit) {
        return credits.findAllByCustomer(customerId, limit);
    }
}
