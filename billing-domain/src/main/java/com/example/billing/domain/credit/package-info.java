/**
 * Credit (선불/프로모 잔액) 도메인.
 *
 * <p>Wallet 과 분리된 별도 잔액 풀. 청구서 차감 전에 적용되어 결제 금액을 줄인다.
 * 발급 사유 ({@link com.example.billing.domain.credit.CreditType}) 별로 만료 정책,
 * 회계 처리, 환불 가능 여부가 다르다.</p>
 *
 * <p>외부 의존: {@code shared} (Money / CustomerId / Reference) 만. invoice / wallet /
 * payment 는 application service 가 조립.</p>
 */
@org.springframework.modulith.NamedInterface("credit")
package com.example.billing.domain.credit;
