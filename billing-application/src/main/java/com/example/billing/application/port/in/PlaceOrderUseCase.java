package com.example.billing.application.port.in;

import com.example.billing.application.command.PlaceOrderCommand;
import com.example.billing.domain.order.Order;

public interface PlaceOrderUseCase {
    Order place(PlaceOrderCommand command);
}
