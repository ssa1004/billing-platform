package com.example.billing.adapter.web.dto

data class AuditEntryView(
    val id: String,
    val actorType: String,            // USER / OPERATOR / SYSTEM / EXTERNAL
    val actorId: String,
    val actorIp: String?,
    val action: String,
    val targetType: String,
    val targetId: String,
    val beforeJson: String?,
    val afterJson: String?,
    val reason: String?,
    val traceId: String?,
    val occurredAt: String,
)

data class AuditEntryListResponse(val items: List<AuditEntryView>)
