package com.example.billing.application.dto

/**
 * DLQ list 응답. cursor 페이지네이션.
 *
 * [nextCursor] 는 마지막 항목의 [DlqMessageView.messageId] (`<topic>:<partition>:<offset>`) —
 * 다음 호출에 `?cursor=...` 로 그대로 전달. 결과가 [size] 보다 적으면 마지막 페이지로 간주하고 null.
 */
@JvmRecord
data class DlqListPage(
    val items: List<DlqMessageView>,
    val nextCursor: String?,
    val size: Int,
)
