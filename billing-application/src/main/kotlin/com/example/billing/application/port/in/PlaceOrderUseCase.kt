package com.example.billing.application.port.`in`

import com.example.billing.application.command.PlaceOrderCommand
import com.example.billing.domain.order.Order

interface PlaceOrderUseCase {
    fun place(command: PlaceOrderCommand): Order
}
