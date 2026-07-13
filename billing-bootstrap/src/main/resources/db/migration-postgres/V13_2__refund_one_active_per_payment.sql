-- payment 당 활성(FAILED 아니고 삭제 안 된) 환불은 최대 1개.
-- 서로 다른 Idempotency-Key 로 같은 결제를 두 번 환불하는 이중 지급을, 앱 계층의 선검사
-- (RefundService.existsActiveByPaymentId) 뒤에서 DB 가 최종 차단하는 동시성 방어선이다.
-- H2 는 partial index 미지원이라 공통 migration 엔 없고, dev/test 는 앱 선검사가 커버한다.
CREATE UNIQUE INDEX uq_refund_active_per_payment
    ON refunds (payment_id)
    WHERE status <> 'FAILED' AND deleted_at IS NULL;
