package com.example.billing.application.port.out

import com.example.billing.domain.order.Order
import com.example.billing.domain.order.OrderId
import java.util.Optional

interface OrderRepository {

    fun save(order: Order)

    fun findById(id: OrderId): Optional<Order>
}
