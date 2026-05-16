package com.example.billing.application.port.`in`

import com.example.billing.application.command.RunSettlementCommand
import com.example.billing.application.command.SettlementResult

interface RunSettlementUseCase {

    /**
     * 한 customer × 한 BillingPeriod 의 정산 실행:
     *  1. advisory lock 으로 동일 customer × period 중복 실행 차단
     *  2. 월 사용량 집계 + 가격 정책으로 청구서 생성
     *  3. 결제 시도 → 결과 기록
     */
    fun run(cmd: RunSettlementCommand): SettlementResult
}
