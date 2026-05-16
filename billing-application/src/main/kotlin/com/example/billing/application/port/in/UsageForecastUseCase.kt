package com.example.billing.application.port.`in`

import com.example.billing.domain.metering.UsageForecast
import com.example.billing.domain.shared.CustomerId

interface UsageForecastUseCase {

    /**
     * 현재 BillingPeriod (asOf 가 속한 월) 의 사용량 → 월말 예상치 + 예상 청구 금액.
     *
     * 방법: 현재까지 누적 사용량 / 월 진행률. 월 진행률이 0 에 가까우면 (월 초 직후)
     * 외삽 신뢰도가 낮으니 호출자가 화면 표시 시 작은 값은 "데이터 부족" 으로 처리하도록
     * [com.example.billing.domain.metering.UsageForecast.periodProgressRatio] 를 함께 반환.
     */
    fun forecastCurrentPeriod(customerId: CustomerId): UsageForecast
}
