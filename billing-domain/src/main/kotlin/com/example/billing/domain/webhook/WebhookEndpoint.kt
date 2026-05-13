package com.example.billing.domain.webhook

import com.example.billing.domain.shared.CustomerId
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.HexFormat
import java.util.Optional

/**
 * Customer 가 등록한 webhook 수신 endpoint.
 *
 * **전체 그림**: 외부 PG 사 (결제 게이트웨이) 가 가맹점에게 결제 결과를 push 통보하는 그
 * 매커니즘의 우리 버전. 여기선 우리가 발신자, customer 가 수신자. customer 가 자기 서버 URL
 * 을 등록해두면 invoice / payment / refund 같은 도메인 이벤트가 발생할 때 그 URL 로 HTTP POST
 * 가 간다.
 *
 * **secret 이 endpoint 단위로 있는 이유 (HMAC 서명 검증)**: customer 서버가 "이 요청이 우리
 * billing 시스템에서 온 게 맞나" 를 검증하기 위해 필요. 동작은:
 * 1. 등록 시 우리가 256-bit 무작위 secret 을 생성 — 응답에 한 번만 평문으로 노출. customer
 *    는 자기 서버에 그 secret 을 보관.
 * 2. 매 webhook 발송 시 우리는 (body + timestamp) 를 secret 으로 HMAC (Hash-based Message
 *    Authentication Code, 비밀 키와 메시지로 만든 위조 방지 서명) 서명해 헤더에 실음.
 * 3. customer 는 같은 secret 으로 다시 계산해 헤더 값과 일치하면 진짜, 아니면 거절. URL 만
 *    알고 있는 공격자 / 중간자가 보낸 가짜 webhook 은 secret 이 없어 같은 값을 만들지 못함.
 *
 * **Secret rotation + grace window (ADR-0029)**: 분실 / 노출 / 정기 갱신 시 [rotateSecret]
 * 으로 새 secret 발급. webhook 발신 SaaS 의 표준 흐름:
 * - 새 secret 을 current secret 으로 활성.
 * - 이전 secret 을 previousSecret 으로 demote — 24h grace window 동안 함께 유효.
 * - 매 webhook 발송 시 두 secret 으로 각각 서명한 두 값을 같은 헤더에 같이 보냄 — customer
 *   가 자기 측 secret 으로 어느 하나라도 일치하면 검증 통과.
 * - 24h 후 previousSecret 은 자동 expire — 그 시점에 customer 는 새 secret 으로 업데이트
 *   되어 있어야 함 (grace 안에서 갱신).
 *
 * 이전 ADR (rotate 즉시 invalidate) 의 단점: customer 가 새 secret 을 반영할 짧은 시간 동안
 * 모든 webhook 이 검증 실패로 떨어짐. grace window 가 deployment overlap 을 자연스럽게 흡수.
 *
 * **subscribedEventTypes 의 default 가 "모든 이벤트"**: customer 가 특정 타입만 받고 싶을 때
 * (예: refund 알림만) 명시. 비어 있으면 모든 이벤트 구독으로 간주 — "기본은 다 받음, 관심
 * 없는 것만 명시적으로 제외" 가 default 여서 온보딩 마찰이 적음.
 *
 * **도메인 invariant**:
 * - URL 은 https 만 허용 (production). 평문 http 로 secret 토큰을 헤더에 실어 보내면 중간자
 *   공격 (man-in-the-middle) 에 secret 이 그대로 노출됨. localhost 만 dev 편의로 예외.
 * - secret 은 32바이트 (256bit) 무작위 값 — HMAC-SHA256 의 권장 키 길이. SecureRandom 사용.
 * - previousSecret 은 (있다면) previousSecretValidUntil 과 항상 짝 — 둘 중 하나만 있으면
 *   invariant 깨짐.
 *
 * **API 호환성**: `previousSecret()` / `previousSecretValidUntil()` 는 Java 측 `Optional` 시그너처
 * 그대로 (WebhookEndpointJpaMapper 가 `.orElse(null)` 로 호출). 그 외 record-style accessor
 * 는 `@get:JvmName` 으로 Java/Kotlin 양쪽 호환.
 */
class WebhookEndpoint private constructor(
    @get:JvmName("id") val id: WebhookEndpointId,
    @get:JvmName("customerId") val customerId: CustomerId,
    @get:JvmName("url") val url: String,
    secret: String,
    previousSecret: String?,
    previousSecretValidUntil: Instant?,
    subscribedEventTypes: Set<String>,
    status: WebhookEndpointStatus,
    @get:JvmName("createdAt") val createdAt: Instant,
    updatedAt: Instant,
    @get:JvmName("version") val version: Long,
) {

    /** 현재 활성 secret. customer 응답에는 한 번만 평문 노출. */
    @get:JvmName("secret")
    var secret: String = secret
        private set

    /** rotation 직후 24h grace 동안 유효한 직전 secret. null = grace window 밖 (rotate 안 했거나 expire). */
    private var _previousSecret: String? = previousSecret

    /** previousSecret 의 만료 시각. null = previousSecret 도 null. */
    private var _previousSecretValidUntil: Instant? = previousSecretValidUntil

    /** 빈 set = 모든 이벤트 구독 (default). 내부는 LinkedHashSet — 순서 보존. */
    private val subscribedEventTypes: LinkedHashSet<String> = LinkedHashSet(subscribedEventTypes)

    @get:JvmName("status")
    var status: WebhookEndpointStatus = status
        private set

    @get:JvmName("updatedAt")
    var updatedAt: Instant = updatedAt
        private set

    init {
        // invariant: previousSecret <-> previousSecretValidUntil 는 짝.
        check((_previousSecret == null) == (_previousSecretValidUntil == null)) {
            "previousSecret and previousSecretValidUntil must both be set or both null"
        }
    }

    /**
     * 이 endpoint 가 주어진 이벤트 타입을 구독했는지.
     * 빈 subscribedEventTypes = 모든 이벤트 구독 (default).
     */
    fun subscribesTo(eventType: String): Boolean {
        if (subscribedEventTypes.isEmpty()) return true
        return subscribedEventTypes.contains(eventType)
    }

    fun pause(clock: Clock) {
        check(status == WebhookEndpointStatus.ACTIVE) {
            "only ACTIVE can be paused: status=$status"
        }
        this.status = WebhookEndpointStatus.PAUSED
        this.updatedAt = clock.instant()
    }

    fun resume(clock: Clock) {
        check(status == WebhookEndpointStatus.PAUSED) {
            "only PAUSED can be resumed: status=$status"
        }
        this.status = WebhookEndpointStatus.ACTIVE
        this.updatedAt = clock.instant()
    }

    /**
     * Secret 을 새로 발급 + 24h grace window 적용 (ADR-0029).
     *
     * 흐름:
     * 1. 현재 secret 을 previousSecret 으로 demote.
     * 2. previousSecretValidUntil 을 now + 24h 로 set.
     * 3. 새 secret 생성, 현재 secret 으로 활성.
     *
     * 이 시점부터 24h 동안 우리 발신 측은 두 secret 모두로 서명한 두 값을 헤더에 같이 보냄.
     * customer 는 자기 측 (이미 갖고 있던 secret 또는 응답으로 받은 새 secret) 으로 어느
     * 한 쪽이라도 일치하면 검증 통과. 24h 후 grace 만료 시점에 customer 는 새 secret 으로
     * 업데이트되어 있어야 함.
     *
     * **grace 안에서 또 rotate 하면**: 앞선 previousSecret 은 덮어씀 — chain 으로 keeping 하지
     * 않는다. (3개 이상 secret 동시 활성은 운영 복잡도만 키우고 의미 없음.) 단기 사이에 두 번
     * rotate 했다면 customer 는 가운데 secret 을 영영 못 보게 되지만, 공격자도 그 secret 을
     * 못 쓰니 보안적으로는 문제없음. 운영 대시보드에 알람만 띄움.
     */
    fun rotateSecret(clock: Clock) {
        rotateSecret(clock, DEFAULT_ROTATION_GRACE)
    }

    /**
     * grace 길이를 명시 — 테스트 / 운영 긴급 (예: secret 이 이미 누출되어 짧은 grace 만 허용)
     * 케이스용. 일반 운영은 [rotateSecret(Clock)] 의 24h 기본값.
     */
    fun rotateSecret(clock: Clock, graceWindow: Duration) {
        require(!graceWindow.isNegative && !graceWindow.isZero) {
            "graceWindow must be positive: $graceWindow"
        }
        val now = clock.instant()
        this._previousSecret = this.secret
        this._previousSecretValidUntil = now.plus(graceWindow)
        this.secret = generateSecret()
        this.updatedAt = now
    }

    /**
     * grace window 가 만료된 previousSecret 을 정리. cron / 운영 작업에서 호출 — 또는 다음
     * rotation 직전에 자연스럽게 처리. lazy cleanup 패턴이라 호출 안 해도 보안적 위험 없음
     * (검증 시 [activeSecrets] 가 만료된 previousSecret 을 자동 제외).
     *
     * @return previousSecret 이 정리되었으면 true.
     */
    fun expirePreviousSecretIfDue(clock: Clock): Boolean {
        val prev = _previousSecret ?: return false
        val until = _previousSecretValidUntil ?: return false
        if (clock.instant().isBefore(until)) return false
        // unused: prev 는 just a null guard
        @Suppress("UNUSED_VARIABLE") val ignored = prev
        this._previousSecret = null
        this._previousSecretValidUntil = null
        this.updatedAt = clock.instant()
        return true
    }

    /**
     * 발신 측이 webhook 서명 시 사용할 활성 secret 목록 — clock 기준으로 grace 안의
     * previousSecret 도 포함.
     *
     * 리스트 순서: 현재 secret 이 항상 첫 번째, previousSecret 은 두 번째 (있을 때). webhook
     * 헤더에 두 서명을 같은 순서로 실음 — customer 가 어느 하나라도 일치하면 통과.
     */
    fun activeSecrets(clock: Clock): List<String> {
        val prev = _previousSecret
        val until = _previousSecretValidUntil
        if (prev != null && until != null && clock.instant().isBefore(until)) {
            return listOf(secret, prev)
        }
        return listOf(secret)
    }

    // ─── record-style accessor (Java 호출자 호환 — 기존 Optional / Set 시그너처 유지) ─────

    /** previousSecret 의 raw 값 — persistence layer 만 사용. 운영 응답에는 노출 금지. */
    fun previousSecret(): Optional<String> = Optional.ofNullable(_previousSecret)

    fun previousSecretValidUntil(): Optional<Instant> = Optional.ofNullable(_previousSecretValidUntil)

    /** 방어적 복사본 반환 — 호출자가 변형해도 내부 상태 영향 없음. */
    fun subscribedEventTypes(): Set<String> = java.util.Set.copyOf(subscribedEventTypes)

    companion object {
        /** secret 길이 (bytes). 256-bit HMAC key. */
        private const val SECRET_BYTES: Int = 32
        private val RNG = SecureRandom()

        /** Rotation grace 기본 길이 — 24h. customer 가 새 secret 을 반영할 충분한 시간. */
        @JvmField
        val DEFAULT_ROTATION_GRACE: Duration = Duration.ofHours(24)

        private fun generateSecret(): String {
            val bytes = ByteArray(SECRET_BYTES)
            RNG.nextBytes(bytes)
            return HexFormat.of().formatHex(bytes)
        }

        private fun validateUrl(url: String) {
            require(url.isNotBlank()) { "url must not be blank" }
            // production 은 https 강제. 로컬 / 테스트 (http://localhost) 만 예외.
            // 호스트 경계까지 정확히 매칭 — "http://localhost.evil.com" 같은 prefix 우회 방어.
            if (url.startsWith("https://")) return
            if (isLocalhostHttp(url)) return
            throw IllegalArgumentException(
                "url must be https (or http://localhost for dev): $url",
            )
        }

        private fun isLocalhostHttp(url: String): Boolean {
            // host 부분만 추출해서 정확히 일치 여부를 확인.
            if (!url.startsWith("http://")) return false
            val afterScheme = url.substring("http://".length)
            // 호스트 종료 문자: ":" (port), "/" (path), "?" (query), "#" (fragment), 끝.
            var hostEnd = afterScheme.length
            for (i in afterScheme.indices) {
                val c = afterScheme[i]
                if (c == ':' || c == '/' || c == '?' || c == '#') {
                    hostEnd = i
                    break
                }
            }
            val host = afterScheme.substring(0, hostEnd)
            return host == "localhost" || host == "127.0.0.1"
        }

        /**
         * 새 endpoint 등록. secret 은 자동 생성 (호출자가 직접 정하지 않음 — 항상 안전한
         * 난수 사용).
         */
        @JvmStatic
        fun register(
            customerId: CustomerId,
            url: String,
            subscribedEventTypes: Set<String>,
            clock: Clock,
        ): WebhookEndpoint {
            validateUrl(url)
            val now = clock.instant()
            return WebhookEndpoint(
                id = WebhookEndpointId.newId(),
                customerId = customerId,
                url = url,
                secret = generateSecret(),
                previousSecret = null,
                previousSecretValidUntil = null,
                subscribedEventTypes = subscribedEventTypes,
                status = WebhookEndpointStatus.ACTIVE,
                createdAt = now,
                updatedAt = now,
                version = 0L,
            )
        }

        @JvmStatic
        fun restore(
            id: WebhookEndpointId,
            customerId: CustomerId,
            url: String,
            secret: String,
            previousSecret: String?,
            previousSecretValidUntil: Instant?,
            subscribedEventTypes: Set<String>,
            status: WebhookEndpointStatus,
            createdAt: Instant,
            updatedAt: Instant,
            version: Long,
        ): WebhookEndpoint = WebhookEndpoint(
            id = id,
            customerId = customerId,
            url = url,
            secret = secret,
            previousSecret = previousSecret,
            previousSecretValidUntil = previousSecretValidUntil,
            subscribedEventTypes = subscribedEventTypes,
            status = status,
            createdAt = createdAt,
            updatedAt = updatedAt,
            version = version,
        )
    }
}
