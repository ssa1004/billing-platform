package com.example.billing.adapter.out.persistence.idempotency;

import com.example.billing.application.port.out.IdempotencyKeyStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InMemoryIdempotencyKeyStoreTest {

    private InMemoryIdempotencyKeyStore store;

    @BeforeEach
    void setUp() {
        store = new InMemoryIdempotencyKeyStore();
    }

    @Test
    void acquire_firstCall_succeeds() {
        assertThatCode(() -> store.acquireOrThrow("key-1")).doesNotThrowAnyException();
    }

    @Test
    void acquire_secondCall_throws() {
        store.acquireOrThrow("key-2");

        assertThatThrownBy(() -> store.acquireOrThrow("key-2"))
                .isInstanceOf(IdempotencyKeyStore.DuplicateRequestException.class);
    }

    @Test
    void acquire_differentKeys_independent() {
        store.acquireOrThrow("a");
        store.acquireOrThrow("b");
        // 둘 다 OK
    }

    @Test
    void cacheResponse_then_findCachedResponse() {
        store.acquireOrThrow("key-3");
        store.cacheResponse("key-3", 200, "{\"id\":\"abc\"}");

        var cached = store.findCachedResponse("key-3");
        assertThat(cached).isPresent();
        assertThat(cached.get().status()).isEqualTo(200);
        assertThat(cached.get().body()).isEqualTo("{\"id\":\"abc\"}");
    }

    @Test
    void findCachedResponse_unknownKey_returnsEmpty() {
        assertThat(store.findCachedResponse("unknown")).isEmpty();
    }

    @Test
    void findCachedResponse_acquiredButNoResponse_returnsEmpty() {
        store.acquireOrThrow("key-4");
        // 아직 응답 캐시 안 함
        assertThat(store.findCachedResponse("key-4")).isEmpty();
    }
}
