package com.example.billing.application.dto

import java.time.Instant

/**
 * DLQ 운영 화면 1줄. payload 본문은 길이만, 헤더는 핵심 항목 (errorClass / topic / partition / offset)
 * 만 노출. 본문 전체는 [DlqMessageDetail] 에서.
 *
 * [messageId] 는 billing 의 합성 식별자 — `<dltTopic>:<partition>:<offset>`. 운영자가 detail /
 * replay / discard 호출 시 이 문자열로 지칭. UUID 가 아닌 이유: Kafka 메시지는 별도 PK 가 없고
 * (topic, partition, offset) 가 자연 키.
 */
@JvmRecord
data class DlqMessageView(
    val messageId: String,
    val source: String,
    val dltTopic: String,
    val originalTopic: String,
    val partition: Int,
    val offset: Long,
    val key: String?,
    val errorClass: String?,
    val failureReason: String?,
    val occurredAt: Instant,
    val payloadLength: Int,
)
