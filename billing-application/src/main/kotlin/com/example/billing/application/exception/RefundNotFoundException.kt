package com.example.billing.application.exception

import com.example.billing.domain.refund.RefundId

class RefundNotFoundException(id: RefundId) : RuntimeException("refund not found: $id")
