package com.example.billing.adapter.out.dlq

import com.example.billing.application.dto.DlqBulkJob
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * [InMemoryDlqBulkJobRepository] 단위 테스트 — 기본 read / write 와 1시간 retention lazy GC 검증.
 */
class InMemoryDlqBulkJobRepositoryTest {

    @Test
    fun createUpdateRead_roundTrip() {
        val repo = InMemoryDlqBulkJobRepository()
        val jobId = UUID.randomUUID()
        val initial = job(jobId, DlqBulkJob.State.RUNNING, 0L, null)
        repo.create(initial)

        val found = repo.findById(jobId)
        assertThat(found).isPresent
        assertThat(found.get()).isEqualTo(initial)

        val updated = job(jobId, DlqBulkJob.State.SUCCEEDED, 10L, Instant.now())
        repo.update(updated)
        assertThat(repo.findById(jobId)).hasValue(updated)
    }

    @Test
    fun findById_unknown_returnsEmpty() {
        val repo = InMemoryDlqBulkJobRepository()
        assertThat(repo.findById(UUID.randomUUID())).isEmpty
    }

    @Test
    fun create_gcSweeps_jobsOlderThanRetention() {
        val repo = InMemoryDlqBulkJobRepository()

        // 1시간 + 1분 전에 끝난 job 을 직접 store 에 박아둠.
        val old = UUID.randomUUID()
        val oldJob = job(
            old,
            DlqBulkJob.State.SUCCEEDED,
            1L,
            Instant.now().minus(Duration.ofMinutes(61)),
        )
        backdoorInject(repo, oldJob)
        assertThat(repo.findById(old)).isPresent

        // 새 job 생성 → gc() 트리거.
        val fresh = UUID.randomUUID()
        repo.create(job(fresh, DlqBulkJob.State.RUNNING, 0L, null))

        assertThat(repo.findById(old)).isEmpty
        assertThat(repo.findById(fresh)).isPresent
    }

    @Test
    fun create_doesNotSweep_finishedWithinRetention() {
        val repo = InMemoryDlqBulkJobRepository()
        val recent = UUID.randomUUID()
        val recentJob = job(
            recent,
            DlqBulkJob.State.SUCCEEDED,
            1L,
            Instant.now().minus(Duration.ofMinutes(10)),
        )
        backdoorInject(repo, recentJob)

        repo.create(job(UUID.randomUUID(), DlqBulkJob.State.RUNNING, 0L, null))

        assertThat(repo.findById(recent)).isPresent
    }

    @Test
    fun create_doesNotSweep_unfinishedJobs() {
        val repo = InMemoryDlqBulkJobRepository()
        val running = UUID.randomUUID()
        val runningJob = job(running, DlqBulkJob.State.RUNNING, 0L, null)
        backdoorInject(repo, runningJob)

        repo.create(job(UUID.randomUUID(), DlqBulkJob.State.RUNNING, 0L, null))

        assertThat(repo.findById(running)).isPresent
    }

    private fun job(id: UUID, state: DlqBulkJob.State, success: Long, finishedAt: Instant?): DlqBulkJob =
        DlqBulkJob(
            id, DlqBulkJob.Operation.REPLAY, state,
            10, 10, success, 10 - success,
            Instant.now().minus(Duration.ofMinutes(5)),
            finishedAt,
            null,
        )

    /**
     * GC 트리거 시점에 cutoff 가 적용되는지 검증을 위해 reflection 으로 store 에 직접 박아넣기.
     * production code 는 변경하지 않고 테스트만의 helper.
     */
    @Suppress("UNCHECKED_CAST")
    private fun backdoorInject(repo: InMemoryDlqBulkJobRepository, job: DlqBulkJob) {
        val f = InMemoryDlqBulkJobRepository::class.java.getDeclaredField("store")
        f.isAccessible = true
        val store = f.get(repo) as MutableMap<UUID, DlqBulkJob>
        store[job.jobId] = job
    }
}
