package com.example.billing.application.port.`in`

import com.example.billing.domain.audit.AuditActor
import com.example.billing.domain.payment.PaymentId
import com.example.billing.domain.refund.RefundId
import java.util.UUID

/**
 * 회계 도메인 row 의 논리 삭제 (soft delete) 진입점 (ADR-0030).
 *
 * 호출자는 운영자 (operator) — 일반 사용자 흐름에는 절대 노출되지 않습니다. 운영자 화면이
 * "삭제" 버튼을 눌렀을 때 호출되며, 실제 행위는:
 *  1. row 의 deleted_at / deleted_by 마킹 (UPDATE 1번)
 *  2. 같은 트랜잭션 안에서 SOFT_DELETED audit entry 발행 — 누가 / 왜 의 영구 기록
 *
 * 둘 중 하나라도 실패하면 둘 다 rollback. "row 는 마킹됐는데 audit 는 누락" 같은 정합 깨짐
 * 사고는 이 트랜잭션 경계로 차단합니다.
 *
 * **이미 삭제된 row 재호출**: 멱등 — 이미 deleted_at 이 set 인 row 에 다시 호출하면
 * UPDATE 가 0행을 영향받아 false 반환. 호출자는 boolean 으로 "처음 삭제" 여부를 안 후 audit 를
 * 한 번만 발행. 두 번 발행되면 timeline 이 어지러워지므로.
 */
interface SoftDeleteUseCase {

    /**
     * Invoice 논리 삭제.
     *
     * @return 처음 삭제 시 true (audit 발행됨), 이미 삭제된 row 였으면 false (audit 발행 X)
     */
    fun softDeleteInvoice(invoiceId: UUID, actor: AuditActor, reason: String): Boolean

    /** Payment 논리 삭제. PG 매칭 row 라 주의 가 더 필요한 작업. */
    fun softDeletePayment(paymentId: PaymentId, actor: AuditActor, reason: String): Boolean

    /** Refund 논리 삭제. */
    fun softDeleteRefund(refundId: RefundId, actor: AuditActor, reason: String): Boolean
}
