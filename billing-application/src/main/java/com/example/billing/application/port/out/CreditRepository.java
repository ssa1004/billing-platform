package com.example.billing.application.port.out;

import com.example.billing.domain.credit.Credit;
import com.example.billing.domain.credit.CreditId;
import com.example.billing.domain.shared.CustomerId;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface CreditRepository {

    void save(Credit credit);

    Optional<Credit> findById(CreditId id);

    /**
     * 차감에 사용 가능한 ACTIVE Credit 들. 만료 임박 → 발급 시점 빠른 순.
     * application service 가 위에서부터 차례로 차감.
     */
    List<Credit> findUsable(CustomerId customerId, Instant now);

    /**
     * 만료 batch 가 호출. {@code valid_until <= now} 인 ACTIVE 들. {@code limit} 까지만.
     * batch 가 결과 0 이 될 때까지 반복 호출.
     */
    List<Credit> findExpiredCandidates(Instant now, int limit);
}
