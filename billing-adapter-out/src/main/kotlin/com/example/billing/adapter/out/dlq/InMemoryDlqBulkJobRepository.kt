package com.example.billing.adapter.out.dlq

import com.example.billing.application.dto.DlqBulkJob
import com.example.billing.application.port.out.DlqBulkJobRepository
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.util.Optional
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * DLQ bulk-replay / bulk-discard job 의 in-memory 보존. 노드 재시작 시 손실 — DB 로 옮기려면
 * 같은 port ([DlqBulkJobRepository]) 의 JPA 어댑터를 추가하면 됨.
 *
 * 1시간 이상 지난 finished job 은 lazy GC — 운영자가 결과 조회 후 잊어도 메모리 누수 X.
 *
 * notification-hub 의 같은 이름 어댑터 (ADR-0015) 와 동일 구조 — billing 패키지로 옮긴
 * 것만 차이.
 */
@Component
class InMemoryDlqBulkJobRepository : DlqBulkJobRepository {

    private val store: ConcurrentHashMap<UUID, DlqBulkJob> = ConcurrentHashMap()

    override fun create(job: DlqBulkJob) {
        gc()
        store[job.jobId] = job
    }

    override fun update(job: DlqBulkJob) {
        store[job.jobId] = job
    }

    override fun findById(jobId: UUID): Optional<DlqBulkJob> = Optional.ofNullable(store[jobId])

    /** lazy GC — create 호출 시점에 finished + retention 지난 항목 제거. */
    private fun gc() {
        val cutoff = Instant.now().minus(RETENTION)
        val it = store.entries.iterator()
        while (it.hasNext()) {
            val job = it.next().value
            val finishedAt = job.finishedAt
            if (finishedAt != null && finishedAt.isBefore(cutoff)) {
                it.remove()
            }
        }
    }

    companion object {
        val RETENTION: Duration = Duration.ofHours(1)
    }
}
