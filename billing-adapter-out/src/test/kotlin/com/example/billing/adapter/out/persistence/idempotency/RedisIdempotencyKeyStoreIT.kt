package com.example.billing.adapter.out.persistence.idempotency

import com.example.billing.application.port.out.IdempotencyKeyStore
import com.redis.testcontainers.RedisContainer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.test.util.ReflectionTestUtils
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName

/**
 * 실제 Redis (Testcontainer) 위에서 SETNX 기반 멱등성 lock 동작 검증.
 *
 * Docker 가 없으면 자동 skip (`@Testcontainers(disabledWithoutDocker = true)`).
 * Spring 컨텍스트 없이 LettuceConnectionFactory 만 직접 만들어 빠르게 검증.
 */
@Testcontainers(disabledWithoutDocker = true)
class RedisIdempotencyKeyStoreIT {

    private lateinit var store: RedisIdempotencyKeyStore

    @BeforeEach
    fun setUp() {
        // 새 스토어 인스턴스 + Redis flush — 각 테스트 격리
        redis.execute<Any?> { c ->
            c.serverCommands().flushAll()
            null
        }
        store = RedisIdempotencyKeyStore(redis)
        // ttlHours 는 @Value 로 주입되니 직접 set
        ReflectionTestUtils.setField(store, "ttlHours", 24L)
    }

    @Test
    fun acquireOrThrow_firstCall_succeeds() {
        store.acquireOrThrow("first-key")
        // 두 번째: 같은 키는 DuplicateRequestException
        assertThatThrownBy { store.acquireOrThrow("first-key") }
            .isInstanceOf(IdempotencyKeyStore.DuplicateRequestException::class.java)
    }

    @Test
    fun acquireOrThrow_differentKeys_independent() {
        store.acquireOrThrow("key-a")
        store.acquireOrThrow("key-b")
        store.acquireOrThrow("key-c")
        // 각각 다른 키는 충돌 없음
    }

    @Test
    fun cacheResponse_thenFindCachedResponse_roundTrip() {
        store.cacheResponse("resp-key", 201, """{"id":"abc"}""")

        val cached = store.findCachedResponse("resp-key")
        assertThat(cached).isPresent
        assertThat(cached.get().status).isEqualTo(201)
        assertThat(cached.get().body).isEqualTo("""{"id":"abc"}""")
    }

    @Test
    fun findCachedResponse_missingKey_empty() {
        assertThat(store.findCachedResponse("nope")).isEmpty
    }

    @Test
    fun cacheResponse_emptyBody_handledCorrectly() {
        store.cacheResponse("empty-body", 204, null)

        val cached = store.findCachedResponse("empty-body")
        assertThat(cached).isPresent
        assertThat(cached.get().status).isEqualTo(204)
        assertThat(cached.get().body).isEmpty()
    }

    companion object {
        private val REDIS: RedisContainer = RedisContainer(DockerImageName.parse("redis:7-alpine"))

        private lateinit var connectionFactory: LettuceConnectionFactory
        private lateinit var redis: StringRedisTemplate

        @JvmStatic
        @BeforeAll
        fun startRedis() {
            REDIS.start()
            connectionFactory = LettuceConnectionFactory(REDIS.host, REDIS.redisPort)
            connectionFactory.afterPropertiesSet()
            redis = StringRedisTemplate(connectionFactory)
            redis.afterPropertiesSet()
        }

        @JvmStatic
        @AfterAll
        fun stopRedis() {
            if (::connectionFactory.isInitialized) connectionFactory.destroy()
            if (REDIS.isRunning) REDIS.stop()
        }
    }
}
