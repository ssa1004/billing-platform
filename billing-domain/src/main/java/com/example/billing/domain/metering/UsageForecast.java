package com.example.billing.domain.metering;

import com.example.billing.domain.settlement.BillingPeriod;
import com.example.billing.domain.shared.CustomerId;
import com.example.billing.domain.shared.Money;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 한 customer × 한 BillingPeriod (청구 기간) 의 현재까지 사용량 (mtd, month-to-date) 과
 * 월말 예상치 를 묶은 값 객체 (value object).
 *
 * <p>외부에서 직접 만들지 않습니다 — application service 가 read model 로 계산해 반환합니다.
 * 쓰기 (write side) 가 없는 읽기 전용 도메인 모델입니다 (DTO 와 다른 점: 단위 / 통화 같은
 * invariant 를 가짐).</p>
 *
 * <p>현재는 단순 선형 외삽 (지금까지의 사용량을 진행률로 나눠 한 달 전체로 늘려 추정) 으로
 * projectedQuantity 를 계산합니다. 정확도가 더 필요하면 7일 이동 평균 / 시계열 모델 (ARIMA
 * / 회귀) 등으로 service 만 교체.</p>
 */
public record UsageForecast(
        CustomerId customerId,
        BillingPeriod period,
        Instant asOf,
        /** [0.0, 1.0] — 0.0 은 기간 시작 직후, 1.0 은 기간 종료 시점. */
        double periodProgressRatio,
        List<ResourceForecast> resources,
        Money projectedTotalCost
) {

    public UsageForecast {
        Objects.requireNonNull(customerId);
        Objects.requireNonNull(period);
        Objects.requireNonNull(asOf);
        Objects.requireNonNull(resources);
        Objects.requireNonNull(projectedTotalCost);
        if (periodProgressRatio < 0.0 || periodProgressRatio > 1.0) {
            throw new IllegalArgumentException("progress out of [0,1]: " + periodProgressRatio);
        }
        resources = List.copyOf(resources);
    }

    public record ResourceForecast(
            ResourceType resourceType,
            long mtdQuantity,
            long projectedQuantity,
            Money mtdCost,
            Money projectedCost
    ) {
        public ResourceForecast {
            Objects.requireNonNull(resourceType);
            Objects.requireNonNull(mtdCost);
            Objects.requireNonNull(projectedCost);
            if (mtdQuantity < 0 || projectedQuantity < 0) {
                throw new IllegalArgumentException("quantity must be non-negative");
            }
            if (projectedQuantity < mtdQuantity) {
                throw new IllegalArgumentException(
                        "projected (" + projectedQuantity + ") must be >= mtd (" + mtdQuantity + ")");
            }
        }
    }
}
