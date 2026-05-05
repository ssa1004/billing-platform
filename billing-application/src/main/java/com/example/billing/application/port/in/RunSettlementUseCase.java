package com.example.billing.application.port.in;

import com.example.billing.application.command.RunSettlementCommand;
import com.example.billing.application.command.SettlementResult;

public interface RunSettlementUseCase {

    /**
     * 한 customer × 한 BillingPeriod 의 정산 실행:
     * <ol>
     *   <li>advisory lock 으로 동일 customer × period 중복 실행 차단</li>
     *   <li>월 사용량 집계 + 가격 정책으로 청구서 생성</li>
     *   <li>결제 시도 → 결과 기록</li>
     * </ol>
     */
    SettlementResult run(RunSettlementCommand cmd);
}
