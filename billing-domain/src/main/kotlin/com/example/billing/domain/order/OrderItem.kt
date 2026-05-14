package com.example.billing.domain.order

import com.example.billing.domain.shared.Money
import java.math.BigDecimal
import java.util.Currency

/**
 * 주문 라인. immutable. 합산 금액은 [Money] 로 통화 정합성 보장.
 *
 * `@JvmRecord data class` — Java 호출자 (`OrderJpaMapper` 의 `new OrderItem(...)` 직접 생성자 +
 * `item.sku()` / `item.unitPrice()` 등 record-style accessor, `OrderTest` 의 `OrderItem.of(...)`)
 * 무변경. compact constructor 검증은 `init` 블록으로 보존.
 */
@JvmRecord
data class OrderItem(
    val sku: String,
    val quantity: Int,
    val unitPrice: Money,
) {

    init {
        require(sku.isNotBlank()) { "sku must not be blank" }
        require(quantity > 0) { "quantity must be positive: $quantity" }
        require(unitPrice.isPositive) { "unitPrice must be positive: $unitPrice" }
    }

    fun lineTotal(): Money = unitPrice.multiply(BigDecimal.valueOf(quantity.toLong()))

    companion object {
        @JvmStatic
        fun of(sku: String, quantity: Int, unitPrice: BigDecimal, currency: Currency): OrderItem =
            OrderItem(sku, quantity, Money.of(unitPrice, currency))
    }
}
