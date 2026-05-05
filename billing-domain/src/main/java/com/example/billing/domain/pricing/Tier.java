package com.example.billing.domain.pricing;

import com.example.billing.domain.metering.ResourceType;
import com.example.billing.domain.shared.Money;

/**
 * 한 resourceType 의 한 구간.
 *
 * <p>예: API_CALL, upTo=10000, unitPrice=0 → 1만 호출까지 무료. 다음 tier 가
 * upTo=null (=무한대), unitPrice=0.001원 → 1만 초과분은 호출당 0.001원.</p>
 *
 * <p>upTo 가 null 이면 마지막 (overage) tier.</p>
 *
 * @param resourceType 어느 리소스에 적용
 * @param upTo 이 구간의 상한 (포함). null = 무한
 * @param unitPrice 이 구간의 단위당 가격
 */
public record Tier(ResourceType resourceType, Long upTo, Money unitPrice) {

    public Tier {
        if (resourceType == null) throw new IllegalArgumentException("resourceType");
        if (unitPrice == null) throw new IllegalArgumentException("unitPrice");
        if (upTo != null && upTo <= 0) {
            throw new IllegalArgumentException("upTo must be positive when set");
        }
    }
}
