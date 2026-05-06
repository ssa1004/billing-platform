-- Invoice 에 Credit 차감 누적 컬럼.
-- amountDue = total_amount - applied_credit. 결제 service / aged receivables 가 사용.
-- 기존 row 는 0 으로 backfill (default).

ALTER TABLE invoices
    ADD COLUMN applied_credit DECIMAL(19, 2) NOT NULL DEFAULT 0;

-- invariant: 0 <= applied_credit <= total_amount
ALTER TABLE invoices
    ADD CONSTRAINT chk_invoice_applied_credit_nonneg CHECK (applied_credit >= 0);
ALTER TABLE invoices
    ADD CONSTRAINT chk_invoice_applied_credit_le_total CHECK (applied_credit <= total_amount);
