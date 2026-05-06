package com.example.billing.application.port.in;

public interface DeliverPendingWebhooksUseCase {

    /**
     * PENDING + nextAttemptAt 도달한 delivery 들을 잡아 HTTP 발송.
     *
     * <p>한 번에 너무 많이 잡으면 한 worker 가 오래 묶임 → {@code limit} 으로 제한. 스케줄러는
     * 결과 0 이 될 때까지 반복 호출 (또는 단순히 매 분 한 번씩 호출 → 누적은 다음 분에).</p>
     *
     * @return 이번 호출에서 처리한 (success / dead / retry queued) 건수
     */
    int deliverBatch(int limit);
}
