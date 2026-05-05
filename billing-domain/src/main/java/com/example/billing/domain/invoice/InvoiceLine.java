package com.example.billing.domain.invoice;

import com.example.billing.domain.metering.ResourceType;
import com.example.billing.domain.shared.Money;

import java.util.Objects;

/**
 * 청구서 line item — 한 resourceType 의 사용량과 청구 금액.
 *
 * <p>{@code unitPriceDescription} 은 사람이 읽는 가격 설명 (예: "처음 1만 호출 무료, 이후
 * 호출당 1원"). UI 표시 + 영수증 발행에 사용.</p>
 */
public record InvoiceLine(
        ResourceType resourceType,
        long quantity,
        Money lineTotal,
        String unitPriceDescription
) {

    public InvoiceLine {
        Objects.requireNonNull(resourceType);
        Objects.requireNonNull(lineTotal);
        Objects.requireNonNull(unitPriceDescription);
        if (quantity < 0) throw new IllegalArgumentException("quantity must be non-negative");
    }
}
