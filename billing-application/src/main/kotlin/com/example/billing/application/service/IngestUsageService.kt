package com.example.billing.application.service

import com.example.billing.application.command.IngestUsageCommand
import com.example.billing.application.port.`in`.IngestUsageUseCase
import com.example.billing.application.port.out.UsageEventRepository
import com.example.billing.domain.metering.UsageEvent
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/**
 * UsageEvent 수신 + DB 저장. 멱등성은 DB UNIQUE constraint 로 강제 (eventId).
 *
 * 고빈도 호출 (수십~수백 RPS 가능) 이므로 가벼움이 핵심. 집계는 별도 batch job 이 처리.
 */
@Service
open class IngestUsageService(
    private val repository: UsageEventRepository,
    private val clock: Clock,
) : IngestUsageUseCase {

    @Transactional
    override fun ingest(cmd: IngestUsageCommand): Boolean {
        val event = UsageEvent.record(
            cmd.eventId, cmd.customerId, cmd.resourceType,
            cmd.quantity, cmd.occurredAt, clock.instant(),
        )
        return repository.saveIfAbsent(event)
    }
}
