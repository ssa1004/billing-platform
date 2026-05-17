package com.example.billing.adapter.out.persistence.jpa.entity

import jakarta.persistence.CascadeType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.OneToMany
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "orders")
class OrderJpaEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    var id: UUID? = null

    @Column(name = "buyer_id", nullable = false, length = 128)
    var buyerId: String = ""

    @Column(name = "total_amount", nullable = false, precision = 19, scale = 4)
    var totalAmount: BigDecimal = BigDecimal.ZERO

    @Column(name = "currency", nullable = false, length = 3)
    var currency: String = ""

    @Column(name = "status", nullable = false, length = 32)
    var status: String = ""

    @Column(name = "payment_id")
    var paymentId: UUID? = null

    @Column(name = "refund_id")
    var refundId: UUID? = null

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.EPOCH

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.EPOCH

    @Version
    @Column(name = "version", nullable = false)
    var version: Long = 0

    @OneToMany(mappedBy = "orderId", cascade = [CascadeType.ALL], orphanRemoval = true, fetch = FetchType.EAGER)
    var items: MutableList<OrderItemJpaEntity> = mutableListOf()
}
