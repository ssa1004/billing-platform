/**
 * Audit log 도메인 — 결제/청구 시스템의 *누가 / 언제 / 무엇을 / 왜* 를 영구 기록.
 *
 * <p>회계 감사 (SOX, 미국 상장 기업 회계 책임법 / 국세청 검증) / 정보보호 (PCI-DSS, 카드
 * 정보 다루는 시스템 보안 표준) / 운영 분쟁 (customer 컴플레인) 의 1차 근거 자료입니다.
 * Append-only — 한 번 기록된 row 는 절대 UPDATE/DELETE 하지 않고 추가만 합니다. 정정도
 * 새 row 로 표현되어 *전체 timeline 자체가 곧 진실* 이 됩니다.</p>
 */
@org.springframework.modulith.NamedInterface("audit")
package com.example.billing.domain.audit;
