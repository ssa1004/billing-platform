package com.example.billing.adapter.out.persistence.jpa;

import com.example.billing.adapter.out.persistence.jpa.mapper.OrderJpaMapper;
import com.example.billing.adapter.out.persistence.jpa.repository.SpringDataOrderRepository;
import com.example.billing.application.port.out.OrderRepository;
import com.example.billing.domain.order.Order;
import com.example.billing.domain.order.OrderId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class JpaOrderRepositoryAdapter implements OrderRepository {

    private final SpringDataOrderRepository jpa;

    @Override
    public void save(Order order) {
        jpa.save(OrderJpaMapper.toEntity(order));
    }

    @Override
    public Optional<Order> findById(OrderId id) {
        return jpa.findById(id.value()).map(OrderJpaMapper::toDomain);
    }
}
