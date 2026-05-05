package com.example.billing.application.port.out;

import com.example.billing.domain.metering.UsageEvent;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * UsageEvent 영속화 + 멱등성 검사.
 *
 * <p>{@link #saveIfAbsent} 는 eventId UNIQUE 제약 위반 시 false 반환 (중복 무시). DB 단에서
 * 멱등성을 보장하므로 application 측 lock 불필요.</p>
 */
public interface UsageEventRepository {

    /** @return true = 새로 저장됨, false = 이미 존재 (멱등성으로 무시) */
    boolean saveIfAbsent(UsageEvent event);

    boolean existsById(UUID eventId);

    List<UsageEvent> findInRange(Instant fromInclusive, Instant toExclusive, int limit);
}
