package com.example.billing.adapter.out.persistence.jpa.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.math.BigDecimal
import java.util.UUID

@Entity
@Table(name = "order_items")
class OrderItemJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    var id: Long? = null

    @Column(name = "order_id", nullable = false)
    var orderId: UUID? = null

    @Column(name = "sku", nullable = false, length = 128)
    var sku: String = ""

    @Column(name = "quantity", nullable = false)
    var quantity: Int = 0

    @Column(name = "unit_price", nullable = false, precision = 19, scale = 4)
    var unitPrice: BigDecimal = BigDecimal.ZERO
}
