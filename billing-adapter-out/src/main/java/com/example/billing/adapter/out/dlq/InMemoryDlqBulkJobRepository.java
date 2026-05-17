package com.example.billing.adapter.out.dlq;

import com.example.billing.application.dto.DlqBulkJob;
import com.example.billing.application.port.out.DlqBulkJobRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * DLQ bulk-replay / bulk-discard job 의 in-memory 보존. 노드 재시작 시 손실 — DB 로 옮기려면
 * 같은 port ({@link DlqBulkJobRepository}) 의 JPA 어댑터를 추가하면 됨.
 *
 * <p>1시간 이상 지난 finished job 은 lazy GC — 운영자가 결과 조회 후 잊어도 메모리 누수 X.
 *
 * <p>notification-hub 의 같은 이름 어댑터 (ADR-0015) 와 동일 구조 — billing 패키지로 옮긴
 * 것만 차이.
 */
@Component
public class InMemoryDlqBulkJobRepository implements DlqBulkJobRepository {

    static final Duration RETENTION = Duration.ofHours(1);

    private final Map<UUID, DlqBulkJob> store = new ConcurrentHashMap<>();

    @Override
    public void create(DlqBulkJob job) {
        gc();
        store.put(job.jobId(), job);
    }

    @Override
    public void update(DlqBulkJob job) {
        store.put(job.jobId(), job);
    }

    @Override
    public Optional<DlqBulkJob> findById(UUID jobId) {
        return Optional.ofNullable(store.get(jobId));
    }

    /** lazy GC — create 호출 시점에 finished + retention 지난 항목 제거. */
    private void gc() {
        Instant cutoff = Instant.now().minus(RETENTION);
        Iterator<Map.Entry<UUID, DlqBulkJob>> it = store.entrySet().iterator();
        while (it.hasNext()) {
            DlqBulkJob job = it.next().getValue();
            if (job.finishedAt() != null && job.finishedAt().isBefore(cutoff)) {
                it.remove();
            }
        }
    }
}
