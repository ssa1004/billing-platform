package com.example.billing.domain.pricing

import com.example.billing.domain.metering.ResourceType
import com.example.billing.domain.shared.Money
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import java.math.BigDecimal
import java.time.Instant
import java.util.Currency
import org.junit.jupiter.api.Test

/**
 * [PricingPlan] / [PricingSnapshot] 의 tier 정규화·검증·스냅샷 경계 케이스.
 *
 * 기존 Java `TieredCalculatorTest` 는 누진 계산의 happy path 를 다뤘다. 여기서는
 * 그 테스트가 비워 둔 부분 — tier 정렬 정규화, resourceType 미정의 가드, 빈 tiers /
 * blank name 검증, snapshot 의 미적용 resourceType → 0원 폴백 — 을 락다운한다.
 * 순수 도메인이라 Spring 불필요.
 */
class PricingPlanTest {

    private val krw: Currency = Currency.getInstance("KRW")
    private val now: Instant = Instant.parse("2026-01-01T00:00:00Z")

    private fun tier(upTo: Long?, price: Long) =
        Tier(ResourceType.API_CALL, upTo, Money.of(BigDecimal.valueOf(price), krw))

    @Test
    fun `upTo 가 뒤섞여 들어와도 누진 계산이 정렬 순서대로 동작한다`() {
        // 일부러 역순으로 입력 — overage tier 를 먼저, 무료 tier 를 나중에
        val plan = PricingPlan.create(
            "Unsorted",
            listOf(
                tier(null, 2),
                tier(1000L, 0),
                tier(10000L, 1),
            ),
            now,
        )

        // 정렬 후: [0..1000]@0 + [1000..10000]@1 + [10000..]@2
        // 15000 = 1000*0 + 9000*1 + 5000*2 = 19000
        assertThat(plan.calculate(ResourceType.API_CALL, 15000L).amount)
            .isEqualByComparingTo("19000")
    }

    @Test
    fun `정의되지 않은 resourceType 으로 calculate 하면 예외`() {
        val plan = PricingPlan.create("ApiOnly", listOf(tier(null, 1)), now)

        assertThatThrownBy { plan.calculate(ResourceType.STORAGE_GB_HOUR, 100L) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("no tier defined")
    }

    @Test
    fun `한 plan 안에 여러 resourceType 이 공존할 수 있다`() {
        val plan = PricingPlan.create(
            "Multi",
            listOf(
                Tier(ResourceType.API_CALL, null, Money.of(1L, krw)),
                Tier(ResourceType.STORAGE_GB_HOUR, null, Money.of(5L, krw)),
            ),
            now,
        )

        assertThat(plan.calculate(ResourceType.API_CALL, 100L).amount).isEqualByComparingTo("100")
        assertThat(plan.calculate(ResourceType.STORAGE_GB_HOUR, 10L).amount).isEqualByComparingTo("50")
    }

    @Test
    fun `빈 tiers 로는 plan 을 만들 수 없다`() {
        assertThatThrownBy { PricingPlan.create("Empty", emptyList(), now) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("tiers must not be empty")
    }

    @Test
    fun `blank name 은 거부된다`() {
        assertThatThrownBy { PricingPlan.create("  ", listOf(tier(null, 1)), now) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("name must not be blank")
    }

    @Test
    fun `restore 는 동일 id 로 plan 을 복원한다`() {
        val id = java.util.UUID.randomUUID()
        val plan = PricingPlan.restore(id, "Restored", listOf(tier(null, 1)), now)

        assertThat(plan.id).isEqualTo(id)
        assertThat(plan.name).isEqualTo("Restored")
    }

    @Test
    fun `snapshot 은 미적용 resourceType 에 대해 0원을 반환한다`() {
        // plan 은 API_CALL 만 정의 → snapshot 에서 STORAGE 조회 시 0원 폴백
        val plan = PricingPlan.create("ApiOnly", listOf(tier(null, 1)), now)
        val snapshot = plan.snapshot(Instant.parse("2026-05-31T00:00:00Z"))

        val result = snapshot.calculate(ResourceType.STORAGE_GB_HOUR, 100L)

        assertThat(result.isZero).isTrue()
        assertThat(result.currency).isEqualTo(krw)
    }

    @Test
    fun `snapshot 의 tiers 는 방어적으로 복사되어 외부 리스트 변경에 영향받지 않는다`() {
        val mutable = mutableListOf(Tier(ResourceType.API_CALL, null, Money.of(1L, krw)))
        val snapshot = PricingSnapshot.of(java.util.UUID.randomUUID(), "Snap", mutable, now)

        mutable.clear()

        assertThat(snapshot.tiers).hasSize(1)
        assertThat(snapshot.calculate(ResourceType.API_CALL, 10L).amount).isEqualByComparingTo("10")
    }
}
