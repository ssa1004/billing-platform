package com.example.billing.application.exception

import java.util.UUID

class InvoiceNotFoundException(id: UUID) : RuntimeException("invoice not found: $id")
