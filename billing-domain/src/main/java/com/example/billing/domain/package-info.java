/**
 * Domain layer — pure DDD aggregates. Spring / JPA / Jackson / Kafka 의존성 0.
 *
 * <p>Aggregates: {@link com.example.billing.domain.wallet.Wallet}, {@link com.example.billing.domain.order.Order},
 * {@link com.example.billing.domain.payment.Payment}, {@link com.example.billing.domain.refund.Refund}.
 * Append-only ledger: {@link com.example.billing.domain.ledger.LedgerEntry}.</p>
 *
 * <p>Spring Modulith 가 이 sub-package 를 모듈로 인식. 다른 모듈이 의존받지 않는 정점 (sink only).</p>
 */
package com.example.billing.domain;
