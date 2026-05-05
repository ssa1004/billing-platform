package com.example.wallet.application.port.out;

import com.example.wallet.domain.order.Order;
import com.example.wallet.domain.order.OrderId;

import java.util.Optional;

public interface OrderRepository {

    void save(Order order);

    Optional<Order> findById(OrderId id);
}
