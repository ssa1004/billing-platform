package com.example.billing.application.port.`in`

/**
 * 3-phase 결제/환불 흐름의 phase 3 (DB tx2) 가 깨졌을 때 PG 와 우리 상태를 동기화.
 *
 * **왜 필요**: ProcessPaymentService / RefundService 는 다음 3단계로 나뉘어 있습니다.
 *  1. Phase 1 (tx) — Idempotency-Key 점유 + PENDING/REQUESTED 영속화
 *  2. Phase 2 — PG 호출 (트랜잭션 밖)
 *  3. Phase 3 (tx2) — 결과 반영 + 이벤트 발행
 *
 * Phase 3 이 실패 (DB 장애 / 노드 재시작 / OOM 등) 하면 우리 쪽은 PENDING/REQUESTED 인데
 * PG 는 이미 처리한 상태가 됩니다. 이 상태가 영원히 안 풀리면 customer 입장에서는 "결제했는데
 * 우리 시스템은 모르는" 자금 부정합. Reconciler 가 stuck 후보를 찾아 같은 idempotency key 로
 * PG lookup 해서 실제 결과를 다시 끌어와 phase 3 을 재시도합니다.
 *
 * **왜 별도 use case 인가**: 순환 의존을 피하고 ProcessPaymentService 와 트랜잭션 경계를
 * 격리하기 위함. ProcessPaymentService 는 외부 호출자 (HTTP / API) 가 직접 부르고, 이 reconciler
 * 는 스케줄러가 부른다 — 같은 코드를 공유할 수도 있지만 retry 정책 / idempotency 동작이 미묘
 * 하게 달라 분리.
 */
interface ReconcilePgFailuresUseCase {

    /**
     * @return 한 사이클에 처리한 row 개수 (payment + refund 합계)
     */
    fun reconcileBatch(): Int
}
