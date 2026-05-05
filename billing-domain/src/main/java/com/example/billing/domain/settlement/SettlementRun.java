package com.example.billing.domain.settlement;

import com.example.billing.domain.shared.CustomerId;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 정산 실행 entity. 한 BillingPeriod × 한 CustomerId 의 정산 1회 실행을 추적.
 *
 * <p>{@code customerId} 가 null 인 행은 "전체 고객 정산" 메타 row 로, 통계 보고용. 실제 처리는
 * customer 별 row 단위로 이뤄짐.</p>
 *
 * <p>상태:
 * <ul>
 *   <li>PENDING — 큐에 들어감</li>
 *   <li>RUNNING — worker 가 잡음</li>
 *   <li>COMPLETED — 청구서 발행 + 결제 시도 모두 끝남 (결제 결과는 별개로 추적)</li>
 *   <li>FAILED — 복구 불가능한 에러 발생, 운영자 개입 필요</li>
 * </ul>
 * </p>
 */
public final class SettlementRun {

    private final UUID id;
    private final BillingPeriod period;
    private final CustomerId customerId;
    private SettlementStatus status;
    private Instant startedAt;
    private Instant finishedAt;
    private int invoicesGenerated;
    private int paymentsAttempted;
    private int paymentsSucceeded;
    private String failureReason;
    private final Instant createdAt;
    private long version;

    private SettlementRun(UUID id, BillingPeriod period, CustomerId customerId,
                          SettlementStatus status, Instant startedAt, Instant finishedAt,
                          int invoicesGenerated, int paymentsAttempted, int paymentsSucceeded,
                          String failureReason, Instant createdAt, long version) {
        this.id = Objects.requireNonNull(id);
        this.period = Objects.requireNonNull(period);
        this.customerId = customerId;
        this.status = Objects.requireNonNull(status);
        this.startedAt = startedAt;
        this.finishedAt = finishedAt;
        this.invoicesGenerated = invoicesGenerated;
        this.paymentsAttempted = paymentsAttempted;
        this.paymentsSucceeded = paymentsSucceeded;
        this.failureReason = failureReason;
        this.createdAt = Objects.requireNonNull(createdAt);
        this.version = version;
    }

    public static SettlementRun queue(BillingPeriod period, CustomerId customerId, Clock clock) {
        return new SettlementRun(UUID.randomUUID(), period, customerId,
                SettlementStatus.PENDING, null, null, 0, 0, 0, null,
                clock.instant(), 0L);
    }

    public static SettlementRun restore(UUID id, BillingPeriod period, CustomerId customerId,
                                        SettlementStatus status, Instant startedAt,
                                        Instant finishedAt, int invoicesGenerated,
                                        int paymentsAttempted, int paymentsSucceeded,
                                        String failureReason, Instant createdAt, long version) {
        return new SettlementRun(id, period, customerId, status, startedAt, finishedAt,
                invoicesGenerated, paymentsAttempted, paymentsSucceeded, failureReason,
                createdAt, version);
    }

    public void start(Clock clock) {
        if (status != SettlementStatus.PENDING) {
            throw new IllegalStateException("cannot start from status " + status);
        }
        this.status = SettlementStatus.RUNNING;
        this.startedAt = clock.instant();
    }

    public void recordResult(int invoicesGenerated, int paymentsAttempted,
                             int paymentsSucceeded, Clock clock) {
        if (status != SettlementStatus.RUNNING) {
            throw new IllegalStateException("cannot record result from status " + status);
        }
        this.invoicesGenerated = invoicesGenerated;
        this.paymentsAttempted = paymentsAttempted;
        this.paymentsSucceeded = paymentsSucceeded;
        this.status = SettlementStatus.COMPLETED;
        this.finishedAt = clock.instant();
    }

    public void fail(String reason, Clock clock) {
        if (status == SettlementStatus.COMPLETED) {
            throw new IllegalStateException("cannot fail completed run");
        }
        this.status = SettlementStatus.FAILED;
        this.failureReason = reason;
        this.finishedAt = clock.instant();
    }

    public UUID id() { return id; }
    public BillingPeriod period() { return period; }
    public Optional<CustomerId> customerId() { return Optional.ofNullable(customerId); }
    public SettlementStatus status() { return status; }
    public Instant startedAt() { return startedAt; }
    public Instant finishedAt() { return finishedAt; }
    public int invoicesGenerated() { return invoicesGenerated; }
    public int paymentsAttempted() { return paymentsAttempted; }
    public int paymentsSucceeded() { return paymentsSucceeded; }
    public String failureReason() { return failureReason; }
    public Instant createdAt() { return createdAt; }
    public long version() { return version; }
}
