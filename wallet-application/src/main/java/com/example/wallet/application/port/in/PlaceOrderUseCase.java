package com.example.wallet.application.port.in;

import com.example.wallet.application.command.PlaceOrderCommand;
import com.example.wallet.domain.order.Order;

public interface PlaceOrderUseCase {
    Order place(PlaceOrderCommand command);
}
