package com.example.billing.application.dto

import java.time.Duration
import java.time.Instant

/**
 * DLQ stats — 시간 bucket 별 메시지 개수 + source / error class 별 cardinality.
 *
 * 운영 화면에서 시간대별 추세와 source별 실패 건수를 조회한다. 일반 page-by-page
 * list 로는 보기 어려움. notification-hub ADR-0015 의 [DlqStats] 와 같은 형태.
 *
 * billing 의 도메인 특유 — `byCustomer` 추가. 같은 customer 의 payment / refund / settlement 가
 * 한꺼번에 실패하는 패턴 (예: 카드 한도 초과로 모든 청구 fail) 을 감지하기 위한 차원.
 */
@JvmRecord
data class DlqStats(
    val from: Instant,
    val to: Instant,
    val bucketDuration: Duration,
    val totalCount: Long,
    val byBucket: List<BucketCount>,
    val bySource: List<KeyedCount>,
    val byErrorClass: List<KeyedCount>,
    val byCustomer: List<KeyedCount>,
) {

    @JvmRecord
    data class BucketCount(
        val bucketStart: Instant,
        val count: Long,
    )

    /** source / errorClass / customer 등 string key 의 count. */
    @JvmRecord
    data class KeyedCount(
        val key: String,
        val count: Long,
    )
}
