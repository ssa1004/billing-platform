package com.example.billing.adapter.out.persistence.idempotency

import com.example.billing.application.port.out.IdempotencyKeyStore
import com.example.billing.application.port.out.IdempotencyKeyStore.CachedResponse
import com.example.billing.application.port.out.IdempotencyKeyStore.DuplicateRequestException
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory 멱등성 키 저장소 — 로컬 dev 전용. `billing.cache.redis-enabled=false` 일 때 활성.
 * 운영은 [RedisIdempotencyKeyStore] (Redis NX SETNX).
 */
@Component
@ConditionalOnProperty(
    name = ["billing.cache.redis-enabled"],
    havingValue = "false",
    matchIfMissing = true,
)
class InMemoryIdempotencyKeyStore : IdempotencyKeyStore {

    private val store: ConcurrentHashMap<String, CachedResponse> = ConcurrentHashMap()

    /** 키 → fingerprint (별도 map — response cache 와 lifecycle 다름). */
    private val fingerprints: ConcurrentHashMap<String, String> = ConcurrentHashMap()

    override fun acquireOrThrow(key: String) {
        if (store.putIfAbsent(key, PLACEHOLDER) != null) {
            throw DuplicateRequestException(key)
        }
    }

    override fun release(key: String) {
        // 캐시된 응답이 이미 있다면 그대로 두고 (재호출 시 같은 응답 반환), placeholder 만 제거.
        store.computeIfPresent(key) { _, v -> if (v === PLACEHOLDER) null else v }
        // fingerprint 도 함께 제거 — release 가 호출되는 시나리오는 첫 요청이 rollback 인데,
        // 이때 다음 retry 가 다른 body 를 보내도 정상 처리되어야 함 (예: 첫 요청에서 입력 검증 실패
        // → client 가 본문을 고쳐 재전송). fingerprint 가 남아있으면 422 로 막혀 정상 흐름 깨짐.
        fingerprints.remove(key)
    }

    override fun cacheResponse(key: String, httpStatus: Int, body: String?) {
        store[key] = CachedResponse(httpStatus, body ?: "")
    }

    override fun findCachedResponse(key: String): Optional<CachedResponse> {
        val v = store[key]
        if (v == null || v === PLACEHOLDER) return Optional.empty()
        return Optional.of(v)
    }

    override fun recordRequestFingerprint(key: String, fingerprint: String) {
        // 첫 호출만 박히도록 putIfAbsent — 동시 두 호출 race 시 한 쪽이 이김. 두 번째 호출은 그대로
        // findRequestFingerprint 의 비교에서 mismatch 검출되어 422.
        fingerprints.putIfAbsent(key, fingerprint)
    }

    override fun findRequestFingerprint(key: String): Optional<String> =
        Optional.ofNullable(fingerprints[key])

    companion object {
        private val PLACEHOLDER = CachedResponse(0, "")
    }
}
