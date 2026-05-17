package com.example.billing.adapter.out.persistence.idempotency

import com.example.billing.application.port.out.IdempotencyKeyStore
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class InMemoryIdempotencyKeyStoreTest {

    private lateinit var store: InMemoryIdempotencyKeyStore

    @BeforeEach
    fun setUp() {
        store = InMemoryIdempotencyKeyStore()
    }

    @Test
    fun acquire_firstCall_succeeds() {
        assertThatCode { store.acquireOrThrow("key-1") }.doesNotThrowAnyException()
    }

    @Test
    fun acquire_secondCall_throws() {
        store.acquireOrThrow("key-2")

        assertThatThrownBy { store.acquireOrThrow("key-2") }
            .isInstanceOf(IdempotencyKeyStore.DuplicateRequestException::class.java)
    }

    @Test
    fun acquire_differentKeys_independent() {
        store.acquireOrThrow("a")
        store.acquireOrThrow("b")
        // 둘 다 OK
    }

    @Test
    fun cacheResponse_then_findCachedResponse() {
        store.acquireOrThrow("key-3")
        store.cacheResponse("key-3", 200, """{"id":"abc"}""")

        val cached = store.findCachedResponse("key-3")
        assertThat(cached).isPresent
        assertThat(cached.get().status).isEqualTo(200)
        assertThat(cached.get().body).isEqualTo("""{"id":"abc"}""")
    }

    @Test
    fun findCachedResponse_unknownKey_returnsEmpty() {
        assertThat(store.findCachedResponse("unknown")).isEmpty
    }

    @Test
    fun findCachedResponse_acquiredButNoResponse_returnsEmpty() {
        store.acquireOrThrow("key-4")
        // 아직 응답 캐시 안 함
        assertThat(store.findCachedResponse("key-4")).isEmpty
    }

    @Test
    fun release_freesAcquiredKeyForRetry() {
        store.acquireOrThrow("key-5")
        // 트랜잭션 rollback 시뮬레이션 — release 호출 후 같은 키 재사용 가능해야 함
        store.release("key-5")

        assertThatCode { store.acquireOrThrow("key-5") }.doesNotThrowAnyException()
    }

    @Test
    fun release_keepsCachedResponse() {
        store.acquireOrThrow("key-6")
        store.cacheResponse("key-6", 201, """{"ok":true}""")
        // release 가 응답 캐시를 지워서는 안 됨 (응답 캐시는 별도 lifecycle).
        store.release("key-6")

        assertThat(store.findCachedResponse("key-6")).isPresent
    }

    @Test
    fun release_unknownKey_isNoop() {
        assertThatCode { store.release("never-acquired") }.doesNotThrowAnyException()
    }

    // ─── ADR-0028: request body fingerprint ─────────────────────────────────

    @Test
    fun recordRequestFingerprint_then_findRequestFingerprint() {
        store.recordRequestFingerprint("key-fp-1", "abcd1234")

        assertThat(store.findRequestFingerprint("key-fp-1")).contains("abcd1234")
    }

    @Test
    fun findRequestFingerprint_unknownKey_returnsEmpty() {
        assertThat(store.findRequestFingerprint("never-set")).isEmpty
    }

    @Test
    fun recordRequestFingerprint_isIdempotent_firstWriteWins() {
        // 첫 호출이 진실 — 같은 키로 두 번째 fingerprint 가 박혀도 첫 값 유지.
        // (race window 에서 두 동시 호출이 모두 record 시도 시 한 쪽만 이김.)
        store.recordRequestFingerprint("key-fp-2", "first-fp")
        store.recordRequestFingerprint("key-fp-2", "second-fp")

        assertThat(store.findRequestFingerprint("key-fp-2")).contains("first-fp")
    }

    @Test
    fun release_alsoClearsFingerprint_soNextRetryCanUseDifferentBody() {
        // rollback 시나리오: 첫 요청이 검증 실패로 rollback → release 호출.
        // 다음 retry 가 고친 body 를 보내도 정상 처리되어야 함 — fingerprint 가 남아있으면 422 로
        // 막혀 사용자 경험 깨짐.
        store.acquireOrThrow("key-fp-3")
        store.recordRequestFingerprint("key-fp-3", "old-fp")
        store.release("key-fp-3")

        assertThat(store.findRequestFingerprint("key-fp-3")).isEmpty
    }
}
