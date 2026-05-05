package com.example.billing.adapter.out.persistence.idempotency;

import com.example.billing.application.port.out.IdempotencyKeyStore;
import com.redis.testcontainers.RedisContainer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 실제 Redis (Testcontainer) 위에서 SETNX 기반 멱등성 lock 동작 검증.
 *
 * <p>Docker 가 없으면 자동 skip ({@code @Testcontainers(disabledWithoutDocker = true)}).
 * Spring 컨텍스트 없이 LettuceConnectionFactory 만 직접 만들어 빠르게 검증.</p>
 */
@Testcontainers(disabledWithoutDocker = true)
class RedisIdempotencyKeyStoreIT {

    private static final RedisContainer REDIS = new RedisContainer(
            DockerImageName.parse("redis:7-alpine"));

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redis;
    private RedisIdempotencyKeyStore store;

    @BeforeAll
    static void startRedis() {
        REDIS.start();
        connectionFactory = new LettuceConnectionFactory(
                REDIS.getHost(), REDIS.getRedisPort());
        connectionFactory.afterPropertiesSet();
        redis = new StringRedisTemplate(connectionFactory);
        redis.afterPropertiesSet();
    }

    @AfterAll
    static void stopRedis() {
        if (connectionFactory != null) connectionFactory.destroy();
        if (REDIS.isRunning()) REDIS.stop();
    }

    @BeforeEach
    void setUp() {
        // 새 스토어 인스턴스 + Redis flush — 각 테스트 격리
        redis.execute((org.springframework.data.redis.connection.RedisConnection c) -> {
            c.serverCommands().flushAll();
            return null;
        });
        store = new RedisIdempotencyKeyStore(redis);
        // ttlHours 는 @Value 로 주입되니 직접 set
        org.springframework.test.util.ReflectionTestUtils.setField(store, "ttlHours", 24L);
    }

    @Test
    void acquireOrThrow_firstCall_succeeds() {
        store.acquireOrThrow("first-key");
        // 두 번째: 같은 키는 DuplicateRequestException
        assertThatThrownBy(() -> store.acquireOrThrow("first-key"))
                .isInstanceOf(IdempotencyKeyStore.DuplicateRequestException.class);
    }

    @Test
    void acquireOrThrow_differentKeys_independent() {
        store.acquireOrThrow("key-a");
        store.acquireOrThrow("key-b");
        store.acquireOrThrow("key-c");
        // 각각 다른 키는 충돌 없음
    }

    @Test
    void cacheResponse_thenFindCachedResponse_roundTrip() {
        store.cacheResponse("resp-key", 201, "{\"id\":\"abc\"}");

        var cached = store.findCachedResponse("resp-key");
        assertThat(cached).isPresent();
        assertThat(cached.get().status()).isEqualTo(201);
        assertThat(cached.get().body()).isEqualTo("{\"id\":\"abc\"}");
    }

    @Test
    void findCachedResponse_missingKey_empty() {
        assertThat(store.findCachedResponse("nope")).isEmpty();
    }

    @Test
    void cacheResponse_emptyBody_handledCorrectly() {
        store.cacheResponse("empty-body", 204, null);

        var cached = store.findCachedResponse("empty-body");
        assertThat(cached).isPresent();
        assertThat(cached.get().status()).isEqualTo(204);
        assertThat(cached.get().body()).isEmpty();
    }
}
