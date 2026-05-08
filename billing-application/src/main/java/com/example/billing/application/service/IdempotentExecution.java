package com.example.billing.application.service;

import com.example.billing.application.port.out.IdempotencyKeyStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Idempotency-Key 점유 + 트랜잭션 rollback 시 자동 release 까지 묶어주는 helper.
 *
 * <p><b>왜 필요한가</b>: {@link IdempotencyKeyStore#acquireOrThrow} 만 단독으로 호출하면,
 * 점유는 즉시 Redis 에 박히는데 그 후 도메인 검증 / 외부 호출에서 트랜잭션이 rollback 되어도
 * Redis 의 점유는 그대로 남습니다. 같은 키로 재시도하면 {@code DuplicateRequestException}
 * 만 계속 떨어져 *legitimate retry 가 막히는 DoS* 가 됩니다 (TTL 24h 동안).
 *
 * <p><b>해결</b>: 점유 직후 {@link TransactionSynchronizationManager} 에 rollback 훅을
 * 등록 → 트랜잭션이 commit 되면 그대로 두고, rollback 되면 {@link IdempotencyKeyStore#release}
 * 호출.
 *
 * <p><b>호출 규약</b>: 반드시 {@code @Transactional} 메서드 안에서 호출. 트랜잭션이 active
 * 가 아니면 훅이 등록되지 않으니 단순 acquire 와 동일 (이 경우 release 책임은 호출자).
 */
@Component
@RequiredArgsConstructor
public class IdempotentExecution {

    private final IdempotencyKeyStore store;

    /**
     * 키 점유 + rollback 시 자동 release.
     *
     * @throws IdempotencyKeyStore.DuplicateRequestException 이미 같은 키로 진행 중인 요청이 있을 때
     */
    public void acquireAndReleaseOnRollback(String key) {
        store.acquireOrThrow(key);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status != TransactionSynchronization.STATUS_COMMITTED) {
                        store.release(key);
                    }
                }
            });
        }
    }
}
