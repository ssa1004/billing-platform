package com.example.billing.domain.metering

/**
 * 과금 가능한 리소스 유형. 새 유형 추가는 PricingPlan / 집계 로직 동반 변경이 필요하므로
 * enum 으로 제한.
 */
enum class ResourceType {
    /** API 호출 — 단위: 호출 1건 */
    API_CALL,

    /** 스토리지 — 단위: GB 시간 (1GB 를 1시간 사용 = 1) */
    STORAGE_GB_HOUR,

    /** 활성 사용자 좌석 — 단위: 일 단위 활성 사용자 수 */
    ACTIVE_USER_SEAT,

    /** 데이터 전송량 — 단위: GB */
    DATA_TRANSFER_GB,
}
