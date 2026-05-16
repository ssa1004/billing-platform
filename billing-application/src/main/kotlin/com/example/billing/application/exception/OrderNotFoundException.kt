package com.example.billing.application.exception

import com.example.billing.domain.order.OrderId

class OrderNotFoundException(id: OrderId) : RuntimeException("order not found: $id")
