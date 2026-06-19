package com.example.billing.domain.pricing

import com.example.billing.domain.metering.ResourceType
import com.example.billing.domain.shared.Money
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import java.util.Currency
import org.junit.jupiter.api.Test

/**
 * [Tier] 생성자 invariant. upTo 는 설정 시 양수여야 한다 (null = 무한대 overage tier).
 */
class TierTest {

    private val krw: Currency = Currency.getInstance("KRW")

    @Test
    fun `upTo 가 null 이면 무한대 overage tier 로 허용`() {
        val tier = Tier(ResourceType.API_CALL, null, Money.of(1L, krw))

        assertThat(tier.upTo).isNull()
    }

    @Test
    fun `upTo 가 0 이면 거부된다`() {
        assertThatThrownBy { Tier(ResourceType.API_CALL, 0L, Money.of(1L, krw)) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("upTo must be positive")
    }

    @Test
    fun `upTo 가 음수면 거부된다`() {
        assertThatThrownBy { Tier(ResourceType.API_CALL, -1L, Money.of(1L, krw)) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("upTo must be positive")
    }
}
