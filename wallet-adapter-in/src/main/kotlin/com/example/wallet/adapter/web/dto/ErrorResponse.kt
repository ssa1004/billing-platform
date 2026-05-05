package com.example.wallet.adapter.web.dto

import java.time.Instant

data class ErrorResponse(
    val code: String,
    val message: String,
    val details: List<String> = emptyList(),
    val traceId: String? = null,
    val timestamp: Instant = Instant.now(),
)
