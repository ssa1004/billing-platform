package com.example.billing.application.service;

import com.example.billing.application.port.out.IdempotencyKeyStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * IdempotentExecution 단위 테스트 — 트랜잭션 rollback 시 키가 자동 release 되는지 검증.
 */
class IdempotentExecutionTest {

    private RecordingStore store;
    private IdempotentExecution execution;

    @BeforeEach
    void setUp() {
        store = new RecordingStore();
        execution = new IdempotentExecution(store);
        // 테스트에서 트랜잭션 synchronization 을 직접 시뮬레이션하기 위해 매번 init.
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void acquire_then_commit_keepsKeyHeld() {
        execution.acquireAndReleaseOnRollback("k1");
        assertThat(store.held).containsKey("k1");

        // 트랜잭션 commit 시뮬레이션
        for (TransactionSynchronization s : TransactionSynchronizationManager.getSynchronizations()) {
            s.afterCompletion(TransactionSynchronization.STATUS_COMMITTED);
        }

        // commit 후에도 키는 그대로 — TTL 까지 점유.
        assertThat(store.held).containsKey("k1");
        assertThat(store.released).doesNotContain("k1");
    }

    @Test
    void acquire_then_rollback_releasesKey() {
        execution.acquireAndReleaseOnRollback("k2");
        assertThat(store.held).containsKey("k2");

        // 트랜잭션 rollback 시뮬레이션
        for (TransactionSynchronization s : TransactionSynchronizationManager.getSynchronizations()) {
            s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        }

        assertThat(store.released).contains("k2");
    }

    @Test
    void acquire_then_unknownStatus_alsoReleases() {
        // STATUS_UNKNOWN (=2) 도 안전하게 release 쪽으로 — commit 이 확실하지 않은 한 풀어준다.
        execution.acquireAndReleaseOnRollback("k3");

        for (TransactionSynchronization s : TransactionSynchronizationManager.getSynchronizations()) {
            s.afterCompletion(TransactionSynchronization.STATUS_UNKNOWN);
        }

        assertThat(store.released).contains("k3");
    }

    @Test
    void duplicateKey_throwsBeforeRegistering() {
        store.held.put("dup", true);
        store.failOnAcquire = "dup";

        assertThatThrownBy(() -> execution.acquireAndReleaseOnRollback("dup"))
                .isInstanceOf(IdempotencyKeyStore.DuplicateRequestException.class);

        // 이미 존재하던 키니까 release 되어선 안 됨 (다른 트랜잭션의 점유를 풀어주면 안 되니까).
        // 또한 synchronization 도 등록되지 않아야 함.
        assertThat(store.released).isEmpty();
        assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
    }

    @Test
    void noActiveTransaction_acquiresButNoSynchronizationRegistered() {
        TransactionSynchronizationManager.clearSynchronization();   // 트랜잭션 없음

        execution.acquireAndReleaseOnRollback("k4");
        assertThat(store.held).containsKey("k4");
        // 등록할 곳이 없으므로 release 자동 호출도 없음 — 이 경우 호출자가 책임 (정책상
        // 일반적으로 @Transactional 안에서만 부르도록 권장).
    }

    /** 테스트용 in-memory 더블 — 단순 대체. */
    private static class RecordingStore implements IdempotencyKeyStore {
        final Map<String, Boolean> held = new ConcurrentHashMap<>();
        final Map<String, CachedResponse> cached = new HashMap<>();
        final Map<String, String> fingerprints = new HashMap<>();
        final java.util.List<String> released = new java.util.ArrayList<>();
        String failOnAcquire;

        @Override public void acquireOrThrow(String key) {
            if (key.equals(failOnAcquire)) throw new DuplicateRequestException(key);
            if (held.putIfAbsent(key, true) != null) throw new DuplicateRequestException(key);
        }
        @Override public void release(String key) {
            held.remove(key);
            fingerprints.remove(key);
            released.add(key);
        }
        @Override public void cacheResponse(String key, int httpStatus, String body) {
            cached.put(key, new CachedResponse(httpStatus, body));
        }
        @Override public Optional<CachedResponse> findCachedResponse(String key) {
            return Optional.ofNullable(cached.get(key));
        }
        @Override public void recordRequestFingerprint(String key, String fingerprint) {
            fingerprints.putIfAbsent(key, fingerprint);
        }
        @Override public Optional<String> findRequestFingerprint(String key) {
            return Optional.ofNullable(fingerprints.get(key));
        }
    }
}
