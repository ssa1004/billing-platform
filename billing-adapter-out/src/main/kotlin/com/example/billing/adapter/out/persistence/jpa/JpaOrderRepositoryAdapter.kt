package com.example.billing.adapter.out.persistence.jpa

import com.example.billing.adapter.out.persistence.jpa.mapper.OrderJpaMapper
import com.example.billing.adapter.out.persistence.jpa.repository.SpringDataOrderRepository
import com.example.billing.application.port.out.OrderRepository
import com.example.billing.domain.order.Order
import com.example.billing.domain.order.OrderId
import org.springframework.stereotype.Repository
import java.util.Optional

@Repository
class JpaOrderRepositoryAdapter(
    private val jpa: SpringDataOrderRepository,
) : OrderRepository {

    override fun save(order: Order) {
        jpa.save(OrderJpaMapper.toEntity(order))
    }

    override fun findById(id: OrderId): Optional<Order> =
        jpa.findById(id.value).map(OrderJpaMapper::toDomain)
}
