/**
 * Budget alert (예산 임계 알림) 도메인.
 *
 * <p>{@link com.example.billing.domain.metering.UsageForecast} 결과를 기준으로 한 customer 의
 * 월말 예상 청구액이 정해둔 임계를 넘으면 알림 이벤트 발행. 도메인은 알림 채널을 모름 —
 * application service 가 {@code CustomerNotifier} 로 dispatch.</p>
 */
@org.springframework.modulith.NamedInterface("budget")
package com.example.billing.domain.budget;
