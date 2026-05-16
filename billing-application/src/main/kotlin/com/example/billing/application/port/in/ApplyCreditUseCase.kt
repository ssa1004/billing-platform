package com.example.billing.application.port.`in`

import com.example.billing.application.command.ApplyCreditCommand
import com.example.billing.domain.shared.Money

interface ApplyCreditUseCase {

    /**
     * 사용 가능한 ACTIVE Credit 들을 만료 임박 / 발급 시점 빠른 순으로 합산해 차감.
     * 차감 합계가 [ApplyCreditCommand.applyAtMost] 를 넘지 않도록 보장.
     *
     * @return 실제 차감된 금액 (= [ApplyCreditCommand.applyAtMost] 보다 작거나 같음)
     */
    fun apply(command: ApplyCreditCommand): Money
}
