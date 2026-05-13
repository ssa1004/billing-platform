package com.example.billing.domain.webhook

import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlin.math.max

/**
 * Webhook 한 건의 전송 시도 기록 (aggregate).
 *
 * 한 도메인 이벤트 (예: InvoiceIssued) 가 발생하면, 구독한 endpoint 마다 Delivery 1개씩
 * 만들어진다. 각 Delivery 는 자체 retry 라이프사이클을 가진다.
 *
 * **retry 분기를 호출자가 결정**: customer 서버가 잠깐 다운되었을 수 있고 (이 경우 잠시 후
 * 다시 시도하면 됨), 영구적으로 잘못된 URL 일 수 있고 (4xx — 다시 시도해도 소용없음), 우리
 * 쪽 timeout 일 수 있다 (네트워크 일시 장애 — 재시도해야 함). HTTP 응답 코드만으로 모든 걸
 * 판단할 수 없기에 재시도냐 dead 냐 결정을 호출자 (HTTP 클라이언트) 가 내려서 도메인에 알려준다.
 *
 * **왜 exponential backoff (간격을 점점 늘리는 재시도)**: customer 서버가 다운된 동안 우리가
 * 1초마다 retry 하면 customer 입장에선 사실상 우리가 DDoS. 1분 → 5분 → 30분 → 2시간 → 12시간
 * 식으로 늦춰가며 시도 → customer 가 복구할 시간 + 우리도 큐가 안 막힘.
 *
 * **왜 dead letter (영구 실패 메시지를 별도로 모아두는 큐) 가 필요한가**: 결국 도달 못 한
 * webhook 도 운영자가 보고 수동 replay 할 수 있어야 함. "5번 다 실패해서 사라짐" 은 사용자
 * 입장에서 끔찍한 경험.
 *
 * **도메인 invariant**:
 * - `attemptCount >= 0`, `attemptCount <= maxAttempts`
 * - SUCCESS / DEAD_LETTERED 는 종착 상태 — 다시 PENDING 으로 가려면 명시적 [replay] 호출만 가능.
 * - nextAttemptAt 은 PENDING 일 때만 의미 있음 (worker 가 query 조건으로 사용).
 *
 * record-style accessor (id(), status() 등) 는 `@get:JvmName` 으로 Java/Kotlin 양쪽 호환.
 */
class WebhookDelivery private constructor(
    @get:JvmName("id") val id: WebhookDeliveryId,
    @get:JvmName("endpointId") val endpointId: WebhookEndpointId,
    @get:JvmName("eventType") val eventType: String,
    /** 직렬화된 JSON 본문. customer 서버에 그대로 전달됨. */
    @get:JvmName("payload") val payload: String,
    status: WebhookDeliveryStatus,
    attemptCount: Int,
    nextAttemptAt: Instant?,
    lastResponseStatus: Int?,
    lastError: String?,
    @get:JvmName("createdAt") val createdAt: Instant,
    updatedAt: Instant,
    deliveredAt: Instant?,
    @get:JvmName("version") val version: Long,
) {

    @get:JvmName("status")
    var status: WebhookDeliveryStatus = status
        private set

    @get:JvmName("attemptCount")
    var attemptCount: Int = attemptCount
        private set

    @get:JvmName("nextAttemptAt")
    var nextAttemptAt: Instant? = nextAttemptAt
        private set

    @get:JvmName("lastResponseStatus")
    var lastResponseStatus: Int? = lastResponseStatus
        private set

    /** 최근 실패 사유 — 운영자 디버깅용. response body 일부 또는 예외 메시지. */
    @get:JvmName("lastError")
    var lastError: String? = lastError
        private set

    @get:JvmName("updatedAt")
    var updatedAt: Instant = updatedAt
        private set

    @get:JvmName("deliveredAt")
    var deliveredAt: Instant? = deliveredAt
        private set

    /**
     * Worker 가 픽업 시 호출 — IN_FLIGHT (보내는 중) 로 전이. attemptCount 1 증가.
     * (attemptCount 는 시도 번호라 픽업 시점에 올림. 결과가 어떻든 시도는 한 셈이니까.)
     */
    fun beginAttempt(clock: Clock) {
        check(status == WebhookDeliveryStatus.PENDING) {
            "only PENDING can begin attempt: status=$status"
        }
        this.status = WebhookDeliveryStatus.IN_FLIGHT
        this.attemptCount += 1
        this.updatedAt = clock.instant()
    }

    /** 2xx 응답 받음 → SUCCESS 종착. */
    fun markSuccess(httpStatus: Int, clock: Clock) {
        check(status == WebhookDeliveryStatus.IN_FLIGHT) {
            "only IN_FLIGHT can succeed: status=$status"
        }
        this.status = WebhookDeliveryStatus.SUCCESS
        this.lastResponseStatus = httpStatus
        this.lastError = null
        val now = clock.instant()
        this.updatedAt = now
        this.deliveredAt = now
    }

    /**
     * 일시 실패 (5xx / timeout / network) — 재시도 가능. 남은 시도 횟수가 있으면 PENDING 으로
     * 돌리고 [nextAttemptAt] 을 backoff 만큼 미룬다. 다 썼으면 DEAD_LETTERED.
     *
     * @param httpStatus null 가능 (네트워크 에러로 응답을 못 받은 경우)
     * @param errorSummary response body 또는 예외 메시지 — 256자 이내로 잘라서 저장 (운영 화면
     *        표시용)
     */
    fun markRetryable(httpStatus: Int?, errorSummary: String?, clock: Clock) {
        check(status == WebhookDeliveryStatus.IN_FLIGHT) {
            "only IN_FLIGHT can be marked retryable: status=$status"
        }
        this.lastResponseStatus = httpStatus
        this.lastError = truncate(errorSummary, 256)
        val now = clock.instant()
        this.updatedAt = now

        if (attemptCount >= MAX_ATTEMPTS) {
            this.status = WebhookDeliveryStatus.DEAD_LETTERED
            return
        }
        // attemptCount 는 1-based — 방금 N번째 시도가 실패했으면 BACKOFFS[N-1] 만큼 대기
        val backoff = BACKOFFS[minOf(attemptCount - 1, BACKOFFS.size - 1)]
        this.status = WebhookDeliveryStatus.PENDING
        this.nextAttemptAt = now.plus(backoff)
    }

    /**
     * 영구 실패 (4xx — URL 잘못됨, customer 측이 거부 등) — 재시도해도 소용없음 → 즉시
     * DEAD_LETTERED.
     *
     * 예외: 408 Request Timeout, 429 Too Many Requests 는 일시 장애일 가능성이 커서 retryable
     * 쪽으로 처리해야 함 (호출자 책임).
     */
    fun markDead(httpStatus: Int?, errorSummary: String?, clock: Clock) {
        check(status == WebhookDeliveryStatus.IN_FLIGHT) {
            "only IN_FLIGHT can be marked dead: status=$status"
        }
        this.status = WebhookDeliveryStatus.DEAD_LETTERED
        this.lastResponseStatus = httpStatus
        this.lastError = truncate(errorSummary, 256)
        this.updatedAt = clock.instant()
    }

    /**
     * 운영자가 수동으로 다시 큐에 넣음. attemptCount 는 유지한다 (이 delivery 의 누적 시도
     * 횟수). "다시 시도해도 또 실패할 가능성이 크지만 운영자가 의도적으로 다시 시도하는 것" —
     * 의도가 분명한 동작.
     */
    fun replay(clock: Clock) {
        check(status == WebhookDeliveryStatus.DEAD_LETTERED) {
            "only DEAD_LETTERED can be replayed: status=$status"
        }
        val now = clock.instant()
        // attemptCount 를 한도 미만으로 낮춰서 다시 곧바로 dead 로 가지 않도록 한다.
        // 정확한 정책은 회사마다 다를 수 있음 — 여기서는 추가 시도 1회 보장으로 결정.
        this.attemptCount = max(0, MAX_ATTEMPTS - 1)
        this.status = WebhookDeliveryStatus.PENDING
        this.nextAttemptAt = now
        this.updatedAt = now
    }

    companion object {
        /** 최대 재시도 횟수. 5번이면 1분 + 5분 + 30분 + 2시간 + 12시간 ≈ 14시간 동안 시도. */
        const val MAX_ATTEMPTS: Int = 5

        /**
         * 재시도 간격. 시도 N 번 실패 후 다음 대기 시간 = `BACKOFFS[N - 1]`.
         * 마지막은 12시간 — 여기까지 갔다면 customer 측이 거의 확실히 길게 다운된 (long outage)
         * 상태라는 뜻.
         */
        private val BACKOFFS: Array<Duration> = arrayOf(
            Duration.ofMinutes(1),
            Duration.ofMinutes(5),
            Duration.ofMinutes(30),
            Duration.ofHours(2),
            Duration.ofHours(12),
        )

        private fun truncate(s: String?, max: Int): String? {
            if (s == null) return null
            return if (s.length <= max) s else s.substring(0, max)
        }

        /**
         * 새 delivery 등록. `nextAttemptAt = now` 이라 worker 가 즉시 집어갈 수 있음.
         */
        @JvmStatic
        fun schedule(
            endpointId: WebhookEndpointId,
            eventType: String,
            payload: String,
            clock: Clock,
        ): WebhookDelivery {
            val now = clock.instant()
            return WebhookDelivery(
                id = WebhookDeliveryId.newId(),
                endpointId = endpointId,
                eventType = eventType,
                payload = payload,
                status = WebhookDeliveryStatus.PENDING,
                attemptCount = 0,
                nextAttemptAt = now,
                lastResponseStatus = null,
                lastError = null,
                createdAt = now,
                updatedAt = now,
                deliveredAt = null,
                version = 0L,
            )
        }

        @JvmStatic
        fun restore(
            id: WebhookDeliveryId,
            endpointId: WebhookEndpointId,
            eventType: String,
            payload: String,
            status: WebhookDeliveryStatus,
            attemptCount: Int,
            nextAttemptAt: Instant?,
            lastResponseStatus: Int?,
            lastError: String?,
            createdAt: Instant,
            updatedAt: Instant,
            deliveredAt: Instant?,
            version: Long,
        ): WebhookDelivery = WebhookDelivery(
            id = id,
            endpointId = endpointId,
            eventType = eventType,
            payload = payload,
            status = status,
            attemptCount = attemptCount,
            nextAttemptAt = nextAttemptAt,
            lastResponseStatus = lastResponseStatus,
            lastError = lastError,
            createdAt = createdAt,
            updatedAt = updatedAt,
            deliveredAt = deliveredAt,
            version = version,
        )
    }
}
