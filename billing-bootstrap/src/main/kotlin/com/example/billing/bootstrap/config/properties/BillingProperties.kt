package com.example.billing.bootstrap.config.properties

import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

/**
 * 모든 billing.* 설정을 typed record 트리로 통합. `@ConfigurationPropertiesScan` 으로 자동 발견.
 *
 * Kotlin 데이터 클래스로 표현 — Spring Boot 3 의 constructor binding 이 단일 생성자
 * 데이터 클래스를 자동으로 인식. `kotlin-spring` 플러그인이 `@Validated` 클래스를 open 으로
 * 만들기 때문에 `@JvmRecord` (final 필요) 는 사용하지 않음. consumer 가 모두 Kotlin 측
 * (`CacheConfig`, `PgRestClientConfig`) 이라 호환성 문제 없음.
 */
@ConfigurationProperties(prefix = "billing")
@Validated
data class BillingProperties(
    @field:NotNull @field:Valid val pg: Pg,
    @field:NotNull @field:Valid val outbox: Outbox,
    @field:NotNull @field:Valid val cache: Cache,
    @field:NotNull @field:Valid val security: Security,
    @field:NotNull @field:Valid val idempotency: Idempotency,
) {

    data class Pg(val enabled: Boolean, @field:NotBlank val baseUrl: String)

    data class Outbox(@field:NotNull @field:Valid val relay: Relay) {
        data class Relay(
            val enabled: Boolean,
            @field:Min(50) val pollIntervalMs: Long,
            @field:Min(1) val batchSize: Int,
            @field:Min(1000) val sendTimeoutMs: Long,
            @field:NotBlank val topicPrefix: String,
        )
    }

    data class Cache(
        val redisEnabled: Boolean,
        @field:Min(1) val localTtlSeconds: Long,
        @field:Min(1) val globalTtlSeconds: Long,
    )

    data class Security(@field:NotNull @field:Valid val jwt: Jwt) {
        data class Jwt(val enabled: Boolean)
    }

    data class Idempotency(@field:Min(1) val ttlHours: Long)
}
