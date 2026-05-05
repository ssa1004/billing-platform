package com.example.billing.domain.settlement;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class BillingPeriodTest {

    @Test
    void from_to_는_월_경계() {
        BillingPeriod p = BillingPeriod.of(2026, 5);
        assertThat(p.fromInclusive()).isEqualTo(Instant.parse("2026-05-01T00:00:00Z"));
        assertThat(p.toExclusive()).isEqualTo(Instant.parse("2026-06-01T00:00:00Z"));
    }

    @Test
    void contains_경계_검증() {
        BillingPeriod p = BillingPeriod.of(2026, 5);
        assertThat(p.contains(Instant.parse("2026-05-01T00:00:00Z"))).isTrue();
        assertThat(p.contains(Instant.parse("2026-05-31T23:59:59.999Z"))).isTrue();
        assertThat(p.contains(Instant.parse("2026-06-01T00:00:00Z"))).isFalse();
        assertThat(p.contains(Instant.parse("2026-04-30T23:59:59Z"))).isFalse();
    }

    @Test
    void containing_은_해당_월을_반환() {
        assertThat(BillingPeriod.containing(Instant.parse("2026-05-15T12:00:00Z")).toKey())
                .isEqualTo("2026-05");
        assertThat(BillingPeriod.containing(Instant.parse("2025-12-31T23:59:59Z")).toKey())
                .isEqualTo("2025-12");
    }

    @Test
    void previous_next_월_단위_이동() {
        BillingPeriod jan = BillingPeriod.of(2026, 1);
        assertThat(jan.previous().toKey()).isEqualTo("2025-12");
        assertThat(jan.next().toKey()).isEqualTo("2026-02");
    }

    @Test
    void toKey_는_yyyy_dash_MM() {
        assertThat(BillingPeriod.of(2026, 5).toKey()).isEqualTo("2026-05");
        assertThat(BillingPeriod.of(2026, 12).toKey()).isEqualTo("2026-12");
    }
}
