package com.example.billing.domain.webhook;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Webhook 한 건의 전송 시도 기록 (aggregate).
 *
 * <p>한 도메인 이벤트 (예: InvoiceIssued) 가 발생하면, 구독한 endpoint 마다 *Delivery 1개씩*
 * 만들어집니다. 각 Delivery 는 자체 retry 라이프사이클을 가집니다.
 *
 * <p><b>retry 분기를 호출자가 결정</b>: customer 서버가 잠깐 다운되었을 수 있고 (이 경우 잠시
 * 후 다시 시도하면 됨), 영구적으로 잘못된 URL 일 수 있고 (4xx — 다시 시도해도 소용없음), 우리
 * 쪽 timeout 일 수 있습니다 (네트워크 일시 장애 — 재시도해야 함). HTTP 응답 코드만으로 모든
 * 걸 판단할 수 없기에 *재시도냐 dead 냐* 결정을 호출자 (HTTP 클라이언트) 가 내려서 도메인에
 * 알려줍니다.
 *
 * <p><b>왜 exponential backoff (간격을 점점 늘리는 재시도)</b>: customer 서버가 다운된 동안
 * 우리가 1초마다 retry 하면 customer 입장에선 사실상 우리가 DDoS 입니다. 1분 → 5분 → 30분 →
 * 2시간 → 12시간 식으로 늦춰가며 시도 → customer 가 복구할 시간 + 우리도 큐가 안 막힙니다.
 *
 * <p><b>왜 dead letter (영구 실패 메시지를 별도로 모아두는 큐) 가 필요한가</b>: 결국 도달
 * 못 한 webhook 도 운영자가 보고 수동 replay 할 수 있어야 합니다. "5번 다 실패해서 사라짐"
 * 은 사용자 입장에서 끔찍한 경험.
 *
 * <p><b>도메인 invariant (불변 조건)</b>:
 * <ul>
 *   <li>{@code attemptCount >= 0}, {@code attemptCount <= maxAttempts}</li>
 *   <li>SUCCESS / DEAD_LETTERED 는 종착 상태 — 다시 PENDING 으로 가려면 명시적
 *       {@link #replay} 호출만 가능.</li>
 *   <li>nextAttemptAt 은 PENDING 일 때만 의미 있음 (worker 가 query 조건으로 사용).</li>
 * </ul>
 */
public final class WebhookDelivery {

    /** 최대 재시도 횟수. 5번이면 1분 + 5분 + 30분 + 2시간 + 12시간 ≈ 14시간 동안 시도. */
    public static final int MAX_ATTEMPTS = 5;

    /**
     * 재시도 간격. 시도 N 번 실패 후 다음 대기 시간 = {@code BACKOFFS[N - 1]}.
     * 마지막은 12시간 — 여기까지 갔다면 customer 측이 거의 확실히 길게 다운된 (long outage)
     * 상태라는 뜻.
     */
    private static final Duration[] BACKOFFS = {
            Duration.ofMinutes(1),
            Duration.ofMinutes(5),
            Duration.ofMinutes(30),
            Duration.ofHours(2),
            Duration.ofHours(12),
    };

    private final WebhookDeliveryId id;
    private final WebhookEndpointId endpointId;
    private final String eventType;
    /** 직렬화된 JSON 본문. customer 서버에 그대로 전달됨. */
    private final String payload;
    private WebhookDeliveryStatus status;
    private int attemptCount;
    private Instant nextAttemptAt;
    private Integer lastResponseStatus;
    /** 최근 실패 사유 — 운영자 디버깅용. response body 일부 또는 예외 메시지. */
    private String lastError;
    private final Instant createdAt;
    private Instant updatedAt;
    private Instant deliveredAt;
    private long version;

    private WebhookDelivery(WebhookDeliveryId id, WebhookEndpointId endpointId,
                            String eventType, String payload,
                            WebhookDeliveryStatus status, int attemptCount, Instant nextAttemptAt,
                            Integer lastResponseStatus, String lastError,
                            Instant createdAt, Instant updatedAt, Instant deliveredAt,
                            long version) {
        this.id = id;
        this.endpointId = endpointId;
        this.eventType = eventType;
        this.payload = payload;
        this.status = status;
        this.attemptCount = attemptCount;
        this.nextAttemptAt = nextAttemptAt;
        this.lastResponseStatus = lastResponseStatus;
        this.lastError = lastError;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.deliveredAt = deliveredAt;
        this.version = version;
    }

    /**
     * 새 delivery 등록. {@code nextAttemptAt = now} 이라 worker 가 즉시 집어갈 수 있음.
     */
    public static WebhookDelivery schedule(WebhookEndpointId endpointId, String eventType,
                                           String payload, Clock clock) {
        Objects.requireNonNull(endpointId);
        Objects.requireNonNull(eventType);
        Objects.requireNonNull(payload);
        Instant now = clock.instant();
        return new WebhookDelivery(
                WebhookDeliveryId.newId(), endpointId, eventType, payload,
                WebhookDeliveryStatus.PENDING, 0, now,
                null, null,
                now, now, null, 0L);
    }

    public static WebhookDelivery restore(WebhookDeliveryId id, WebhookEndpointId endpointId,
                                          String eventType, String payload,
                                          WebhookDeliveryStatus status, int attemptCount,
                                          Instant nextAttemptAt,
                                          Integer lastResponseStatus, String lastError,
                                          Instant createdAt, Instant updatedAt, Instant deliveredAt,
                                          long version) {
        return new WebhookDelivery(id, endpointId, eventType, payload, status, attemptCount,
                nextAttemptAt, lastResponseStatus, lastError, createdAt, updatedAt, deliveredAt, version);
    }

    /**
     * Worker 가 픽업 시 호출 — IN_FLIGHT (보내는 중) 로 전이. attemptCount 1 증가.
     * (attemptCount 는 *시도 번호* 라 픽업 시점에 올립니다. 결과가 어떻든 시도는 한 셈이니까.)
     */
    public void beginAttempt(Clock clock) {
        if (status != WebhookDeliveryStatus.PENDING) {
            throw new IllegalStateException("only PENDING can begin attempt: status=" + status);
        }
        this.status = WebhookDeliveryStatus.IN_FLIGHT;
        this.attemptCount++;
        this.updatedAt = clock.instant();
    }

    /**
     * 2xx 응답 받음 → SUCCESS 종착.
     */
    public void markSuccess(int httpStatus, Clock clock) {
        if (status != WebhookDeliveryStatus.IN_FLIGHT) {
            throw new IllegalStateException("only IN_FLIGHT can succeed: status=" + status);
        }
        this.status = WebhookDeliveryStatus.SUCCESS;
        this.lastResponseStatus = httpStatus;
        this.lastError = null;
        Instant now = clock.instant();
        this.updatedAt = now;
        this.deliveredAt = now;
    }

    /**
     * 일시 실패 (5xx / timeout / network) — 재시도 가능. 남은 시도 횟수가 있으면 PENDING 으로
     * 돌리고 {@code nextAttemptAt} 을 backoff 만큼 미룹니다. 다 썼으면 DEAD_LETTERED.
     *
     * @param httpStatus null 가능 (네트워크 에러로 응답을 못 받은 경우)
     * @param errorSummary response body 또는 예외 메시지 — 256자 이내로 잘라서 저장 (운영 화면
     *                     표시용)
     */
    public void markRetryable(Integer httpStatus, String errorSummary, Clock clock) {
        if (status != WebhookDeliveryStatus.IN_FLIGHT) {
            throw new IllegalStateException("only IN_FLIGHT can be marked retryable: status=" + status);
        }
        this.lastResponseStatus = httpStatus;
        this.lastError = truncate(errorSummary, 256);
        Instant now = clock.instant();
        this.updatedAt = now;

        if (attemptCount >= MAX_ATTEMPTS) {
            this.status = WebhookDeliveryStatus.DEAD_LETTERED;
            return;
        }
        // attemptCount 는 1-based — 방금 N번째 시도가 실패했으면 BACKOFFS[N-1] 만큼 대기
        Duration backoff = BACKOFFS[Math.min(attemptCount - 1, BACKOFFS.length - 1)];
        this.status = WebhookDeliveryStatus.PENDING;
        this.nextAttemptAt = now.plus(backoff);
    }

    /**
     * 영구 실패 (4xx — URL 잘못됨, customer 측이 거부 등) — 재시도해도 소용없음 → 즉시
     * DEAD_LETTERED.
     *
     * <p>예외: 408 Request Timeout, 429 Too Many Requests 는 일시 장애일 가능성이 커서
     * retryable 쪽으로 처리해야 함 (호출자 책임).</p>
     */
    public void markDead(Integer httpStatus, String errorSummary, Clock clock) {
        if (status != WebhookDeliveryStatus.IN_FLIGHT) {
            throw new IllegalStateException("only IN_FLIGHT can be marked dead: status=" + status);
        }
        this.status = WebhookDeliveryStatus.DEAD_LETTERED;
        this.lastResponseStatus = httpStatus;
        this.lastError = truncate(errorSummary, 256);
        this.updatedAt = clock.instant();
    }

    /**
     * 운영자가 수동으로 다시 큐에 넣음. attemptCount 는 *유지* 합니다 (이 delivery 의 누적
     * 시도 횟수). "다시 시도해도 또 실패할 가능성이 크지만 운영자가 의도적으로 다시
     * 시도하는 것" — 의도가 분명한 동작.
     */
    public void replay(Clock clock) {
        if (status != WebhookDeliveryStatus.DEAD_LETTERED) {
            throw new IllegalStateException("only DEAD_LETTERED can be replayed: status=" + status);
        }
        Instant now = clock.instant();
        // attemptCount 를 한도 미만으로 낮춰서 다시 곧바로 dead 로 가지 않도록 합니다.
        // 정확한 정책은 회사마다 다를 수 있음 — 여기서는 추가 시도 1회 보장으로 결정.
        this.attemptCount = Math.max(0, MAX_ATTEMPTS - 1);
        this.status = WebhookDeliveryStatus.PENDING;
        this.nextAttemptAt = now;
        this.updatedAt = now;
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    // Getters
    public WebhookDeliveryId id() { return id; }
    public WebhookEndpointId endpointId() { return endpointId; }
    public String eventType() { return eventType; }
    public String payload() { return payload; }
    public WebhookDeliveryStatus status() { return status; }
    public int attemptCount() { return attemptCount; }
    public Instant nextAttemptAt() { return nextAttemptAt; }
    public Integer lastResponseStatus() { return lastResponseStatus; }
    public String lastError() { return lastError; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
    public Instant deliveredAt() { return deliveredAt; }
    public long version() { return version; }
}
