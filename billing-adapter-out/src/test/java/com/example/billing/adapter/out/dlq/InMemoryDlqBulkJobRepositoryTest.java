package com.example.billing.adapter.out.dlq;

import com.example.billing.application.dto.DlqBulkJob;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link InMemoryDlqBulkJobRepository} 단위 테스트 — 기본 read / write 와 1시간 retention lazy
 * GC 검증.
 */
class InMemoryDlqBulkJobRepositoryTest {

    @Test
    void createUpdateRead_roundTrip() {
        InMemoryDlqBulkJobRepository repo = new InMemoryDlqBulkJobRepository();
        UUID jobId = UUID.randomUUID();
        DlqBulkJob initial = job(jobId, DlqBulkJob.State.RUNNING, 0L, null);
        repo.create(initial);

        Optional<DlqBulkJob> found = repo.findById(jobId);
        assertThat(found).isPresent().get().isEqualTo(initial);

        DlqBulkJob updated = job(jobId, DlqBulkJob.State.SUCCEEDED, 10L, Instant.now());
        repo.update(updated);
        assertThat(repo.findById(jobId)).hasValue(updated);
    }

    @Test
    void findById_unknown_returnsEmpty() {
        InMemoryDlqBulkJobRepository repo = new InMemoryDlqBulkJobRepository();
        assertThat(repo.findById(UUID.randomUUID())).isEmpty();
    }

    @Test
    void create_gcSweeps_jobsOlderThanRetention() throws Exception {
        InMemoryDlqBulkJobRepository repo = new InMemoryDlqBulkJobRepository();

        // 1시간 + 1분 전에 끝난 job 을 직접 store 에 박아둠.
        UUID old = UUID.randomUUID();
        DlqBulkJob oldJob = job(old, DlqBulkJob.State.SUCCEEDED, 1L,
                Instant.now().minus(Duration.ofMinutes(61)));
        backdoorInject(repo, oldJob);
        assertThat(repo.findById(old)).isPresent();

        // 새 job 생성 → gc() 트리거.
        UUID fresh = UUID.randomUUID();
        repo.create(job(fresh, DlqBulkJob.State.RUNNING, 0L, null));

        assertThat(repo.findById(old)).isEmpty();
        assertThat(repo.findById(fresh)).isPresent();
    }

    @Test
    void create_doesNotSweep_finishedWithinRetention() throws Exception {
        InMemoryDlqBulkJobRepository repo = new InMemoryDlqBulkJobRepository();
        UUID recent = UUID.randomUUID();
        DlqBulkJob recentJob = job(recent, DlqBulkJob.State.SUCCEEDED, 1L,
                Instant.now().minus(Duration.ofMinutes(10)));
        backdoorInject(repo, recentJob);

        repo.create(job(UUID.randomUUID(), DlqBulkJob.State.RUNNING, 0L, null));

        assertThat(repo.findById(recent)).isPresent();
    }

    @Test
    void create_doesNotSweep_unfinishedJobs() throws Exception {
        InMemoryDlqBulkJobRepository repo = new InMemoryDlqBulkJobRepository();
        UUID running = UUID.randomUUID();
        DlqBulkJob runningJob = job(running, DlqBulkJob.State.RUNNING, 0L, null);
        backdoorInject(repo, runningJob);

        repo.create(job(UUID.randomUUID(), DlqBulkJob.State.RUNNING, 0L, null));

        assertThat(repo.findById(running)).isPresent();
    }

    private static DlqBulkJob job(UUID id, DlqBulkJob.State state, long success, Instant finishedAt) {
        return new DlqBulkJob(
                id, DlqBulkJob.Operation.REPLAY, state,
                10, 10, success, 10 - success,
                Instant.now().minus(Duration.ofMinutes(5)),
                finishedAt,
                null);
    }

    /**
     * GC 트리거 시점에 cutoff 가 적용되는지 검증을 위해 reflection 으로 store 에 직접 박아넣기.
     * production code 는 변경하지 않고 테스트만의 helper.
     */
    @SuppressWarnings("unchecked")
    private static void backdoorInject(InMemoryDlqBulkJobRepository repo, DlqBulkJob job)
            throws Exception {
        Field f = InMemoryDlqBulkJobRepository.class.getDeclaredField("store");
        f.setAccessible(true);
        Map<UUID, DlqBulkJob> store = (Map<UUID, DlqBulkJob>) f.get(repo);
        store.put(job.jobId(), job);
    }
}
