/**
 * Audit log 도메인 — 결제/청구 시스템의 *누가 / 언제 / 무엇을 / 왜* 를 영구 기록.
 *
 * <p>회계 감사 (SOX / 국세청 검증) / 정보보호 (PCI-DSS) / 운영 분쟁 (customer 컴플레인) 의
 * 1차 근거 자료. Append-only — 한 번 기록된 row 는 절대 UPDATE/DELETE 안 함. 정정도
 * 새 row 로 표현되어 *전체 timeline* 이 곧 진실.</p>
 */
@org.springframework.modulith.NamedInterface("audit")
package com.example.billing.domain.audit;
