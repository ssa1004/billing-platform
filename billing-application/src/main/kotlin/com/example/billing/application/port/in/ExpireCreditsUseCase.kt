package com.example.billing.application.port.`in`

interface ExpireCreditsUseCase {

    /**
     * 만료 시점 도달한 ACTIVE Credit 들을 batch 단위로 EXPIRED 처리.
     *
     * @param limit 한 호출에 처리할 최대 건수 (메모리 / lock contention 제어)
     * @return 실제로 EXPIRED 로 전이된 건수
     */
    fun expireBatch(limit: Int): Int
}
