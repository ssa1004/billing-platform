package com.example.billing.application.service;

import com.example.billing.application.port.out.IdempotencyKeyStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Idempotency-Key 점유 + 트랜잭션 rollback 시 자동 release 까지 묶어주는 helper.
 *
 * <p><b>Idempotency-Key 가 뭐였는지</b>: 클라이언트가 같은 요청을 두 번 보냈을 때 두 번 처리되지
 * 않게 막는 키. 보통 클라이언트가 UUID 같은 키를 헤더로 보내고, 서버는 처음 받은 키를 일정 시간
 * (TTL, 여기선 24시간) 동안 점유 상태로 들고 있다가 같은 키로 또 들어오면 거절합니다.</p>
 *
 * <p><b>이 helper 가 푸는 문제</b>: {@link IdempotencyKeyStore#acquireOrThrow} 만 단독으로
 * 호출하면 점유는 Redis 에 즉시 박히는데, 그 직후 도메인 검증이 실패해 트랜잭션이 rollback
 * 되어도 Redis 의 점유는 그대로 남습니다. 같은 키로 다시 시도하면 24h 동안
 * {@code DuplicateRequestException} 만 떨어져 — 정상 재시도가 막히는 *self-DoS* 상황이
 * 됩니다.</p>
 *
 * <p><b>해결 방법</b>: 점유 직후 {@link TransactionSynchronizationManager} 에 후처리 훅을
 * 등록 → 트랜잭션이 commit 되면 점유는 그대로 두고 (성공한 키는 재시도 금지), rollback 되면
 * {@link IdempotencyKeyStore#release} 를 호출해 점유를 풀어줍니다.</p>
 *
 * <p><b>호출 규약</b>: 반드시 {@code @Transactional} 메서드 (또는 활성 트랜잭션) 안에서 호출.
 * 트랜잭션이 active 가 아니면 훅이 등록되지 않아 단순 acquire 와 같아지므로, 이 경우 release
 * 책임은 호출자에게 넘어갑니다.</p>
 *
 * <p><b>응답 캐시 (24h, 결제 API 표준 패턴)</b>는 별도 계층에서 처리합니다 — 인터셉터
 * ({@code IdempotencyResponseCacheFilter}) 가 들어올 때 hit 체크, 나갈 때 store.
 * 이 helper 는 점유 (lock) 만 책임. ADR-0024 참고.</p>
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
