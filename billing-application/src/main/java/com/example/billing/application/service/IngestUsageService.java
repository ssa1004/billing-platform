package com.example.billing.application.service;

import com.example.billing.application.command.IngestUsageCommand;
import com.example.billing.application.port.in.IngestUsageUseCase;
import com.example.billing.application.port.out.UsageEventRepository;
import com.example.billing.domain.metering.UsageEvent;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

/**
 * UsageEvent 수신 + DB 저장. 멱등성은 DB UNIQUE constraint 로 강제 (eventId).
 *
 * <p>고빈도 호출 (수십~수백 RPS 가능) 이므로 가벼움이 핵심. 집계는 별도 batch job 이 처리.</p>
 */
@Service
public class IngestUsageService implements IngestUsageUseCase {

    private final UsageEventRepository repository;
    private final Clock clock;

    public IngestUsageService(UsageEventRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Override
    @Transactional
    public boolean ingest(IngestUsageCommand cmd) {
        UsageEvent event = UsageEvent.record(
                cmd.eventId(), cmd.customerId(), cmd.resourceType(),
                cmd.quantity(), cmd.occurredAt(), clock.instant());
        return repository.saveIfAbsent(event);
    }
}
