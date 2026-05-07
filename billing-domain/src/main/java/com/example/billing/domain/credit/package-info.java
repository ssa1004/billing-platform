/**
 * Credit (선불 충전 / 프로모 / 보상으로 발급된 잔액) 도메인.
 *
 * <p>Wallet (현금성 잔액) 과 분리된 별도 잔액 풀입니다. 청구서 결제 직전에 적용되어 실제
 * 결제 대상 금액을 줄입니다. 발급 사유 ({@link com.example.billing.domain.credit.CreditType})
 * 별로 만료 정책, 회계 처리, 환불 가능 여부가 다릅니다.</p>
 *
 * <p>외부 의존성은 {@code shared} (Money / CustomerId / Reference) 만 사용합니다. invoice /
 * wallet / payment 와의 협업은 application service 가 조립합니다.</p>
 */
@org.springframework.modulith.NamedInterface("credit")
package com.example.billing.domain.credit;
