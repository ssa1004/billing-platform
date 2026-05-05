package com.example.wallet.bootstrap.config.properties;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 모든 wallet.* 설정을 typed record 트리로 통합. {@code @ConfigurationPropertiesScan} 으로 자동 발견.
 */
@ConfigurationProperties(prefix = "wallet")
@Validated
public record WalletProperties(
        @NotNull @Valid Pg pg,
        @NotNull @Valid Outbox outbox,
        @NotNull @Valid Cache cache,
        @NotNull @Valid Security security,
        @NotNull @Valid Idempotency idempotency
) {

    public record Pg(boolean enabled, @NotBlank String baseUrl) {}

    public record Outbox(@NotNull @Valid Relay relay) {
        public record Relay(
                boolean enabled,
                @Min(50) long pollIntervalMs,
                @Min(1) int batchSize,
                @Min(1000) long sendTimeoutMs,
                @NotBlank String topicPrefix
        ) {}
    }

    public record Cache(
            boolean redisEnabled,
            @Min(1) long localTtlSeconds,
            @Min(1) long globalTtlSeconds
    ) {}

    public record Security(@NotNull @Valid Jwt jwt) {
        public record Jwt(boolean enabled) {}
    }

    public record Idempotency(@Min(1) long ttlHours) {}
}
