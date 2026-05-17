package com.example.billing.adapter.out.persistence.idempotency

import com.example.billing.application.port.out.IdempotencyKeyStore
import com.example.billing.application.port.out.IdempotencyKeyStore.CachedResponse
import com.example.billing.application.port.out.IdempotencyKeyStore.DuplicateRequestException
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.Optional

/**
 * Redis 기반 멱등성 키 저장소 — SET NX EX 한 번에 (atomic).
 *
 * 키 구조:
 *  - `billing:idempotency:lock:<key>` = "1" (TTL 24h) — 점유 lock
 *  - `billing:idempotency:resp:<key>` = "<status>|<body>" (TTL 24h) — 응답 캐시
 *  - `billing:idempotency:fp:<key>` = "<sha256-prefix-hex>" (TTL 24h) — 요청 body fingerprint
 *    (ADR-0028)
 */
@Component
@ConditionalOnProperty(name = ["billing.cache.redis-enabled"], havingValue = "true")
class RedisIdempotencyKeyStore(
    private val redis: StringRedisTemplate,
) : IdempotencyKeyStore {

    @Value("\${billing.idempotency.ttl-hours:24}")
    private var ttlHours: Long = 24

    override fun acquireOrThrow(key: String) {
        val acquired = redis.opsForValue().setIfAbsent(LOCK_PREFIX + key, "1", Duration.ofHours(ttlHours))
        if (acquired != true) {
            throw DuplicateRequestException(key)
        }
    }

    override fun release(key: String) {
        // 캐시된 응답은 그대로 두고 (재호출 시 같은 응답 반환), 점유 lock 만 제거.
        // fingerprint 도 함께 제거 — 첫 요청이 rollback 됐으면 다음 retry 가 다른 body 를 보내도
        // 정상 처리되어야 함 (예: 입력 검증 실패 → client 가 본문 고쳐 재전송).
        redis.delete(LOCK_PREFIX + key)
        redis.delete(FP_PREFIX + key)
    }

    override fun cacheResponse(key: String, httpStatus: Int, body: String?) {
        val value = "$httpStatus|${body ?: ""}"
        redis.opsForValue().set(RESP_PREFIX + key, value, Duration.ofHours(ttlHours))
    }

    override fun findCachedResponse(key: String): Optional<CachedResponse> {
        val value = redis.opsForValue().get(RESP_PREFIX + key) ?: return Optional.empty()
        // 형식: "<status>|<body>". 깨진 형식 (구분자 없음 / 숫자 아닌 status) 은
        // 캐시 미스로 처리 — 운영 중 형식이 바뀌었거나 외부 변조 시 NPE / parseException 으로
        // 호출자가 죽지 않도록.
        val sep = value.indexOf('|')
        if (sep <= 0) return Optional.empty()
        val status = try {
            value.substring(0, sep).toInt()
        } catch (e: NumberFormatException) {
            return Optional.empty()
        }
        val body = value.substring(sep + 1)
        return Optional.of(CachedResponse(status, body))
    }

    override fun recordRequestFingerprint(key: String, fingerprint: String) {
        // setIfAbsent — 첫 호출만 박힘. 두 번째 호출은 mismatch 면 422 검출, 같으면 정상 흐름.
        redis.opsForValue().setIfAbsent(FP_PREFIX + key, fingerprint, Duration.ofHours(ttlHours))
    }

    override fun findRequestFingerprint(key: String): Optional<String> =
        Optional.ofNullable(redis.opsForValue().get(FP_PREFIX + key))

    companion object {
        private const val LOCK_PREFIX = "billing:idempotency:lock:"
        private const val RESP_PREFIX = "billing:idempotency:resp:"
        private const val FP_PREFIX = "billing:idempotency:fp:"
    }
}
