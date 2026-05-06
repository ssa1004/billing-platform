package com.example.billing.application.command;

import com.example.billing.domain.shared.Money;

import java.util.UUID;

/**
 * 청구서에 사용 가능한 크레딧 적용. {@code applyAtMost} 까지만 차감.
 *
 * <p>호출자는 보통 invoice 발행 직후 — invoice 의 총액과 customer 의 사용 가능 크레딧 잔액 중
 * 적은 만큼 차감되고, 결과가 invoice 의 결제 대상 금액에서 제외됨. (Invoice 와의 연동은 별도
 * service. 이 command 는 차감만 책임.)</p>
 */
public record ApplyCreditCommand(
        String customerId,
        UUID invoiceId,
        Money applyAtMost
) {}
