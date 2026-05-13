package com.example.billing.domain.pricing

import com.example.billing.domain.metering.ResourceType
import com.example.billing.domain.shared.Money

/**
 * 한 resourceType 의 한 구간.
 *
 * 예: API_CALL, upTo=10000, unitPrice=0 → 1만 호출까지 무료. 다음 tier 가 upTo=null
 * (=무한대), unitPrice=0.001원 → 1만 초과분은 호출당 0.001원.
 *
 * upTo 가 null 이면 마지막 (overage) tier.
 *
 * `@JvmRecord data class` — Java 호출자 (JpaPricingPlanRepositoryAdapter.TierDto.from /
 * JpaInvoiceRepositoryAdapter / MockInvoicePdfRenderer / TieredCalculatorTest 의
 * `new Tier(...)` 직접 생성자 호출 + `t.upTo()` / `t.unitPrice()` / `t.resourceType()`
 * record-style accessor + `Tier::resourceType` 메서드 참조) 모두 그대로 동작.
 *
 * @param resourceType 어느 리소스에 적용
 * @param upTo 이 구간의 상한 (포함). null = 무한
 * @param unitPrice 이 구간의 단위당 가격
 */
@JvmRecord
data class Tier(
    val resourceType: ResourceType,
    val upTo: Long?,
    val unitPrice: Money,
) {
    init {
        require(upTo == null || upTo > 0) { "upTo must be positive when set" }
    }
}
