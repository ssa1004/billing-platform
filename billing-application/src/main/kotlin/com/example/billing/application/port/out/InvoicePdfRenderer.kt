package com.example.billing.application.port.out

import com.example.billing.domain.invoice.Invoice

/**
 * 청구서 PDF 렌더러 — 고객에게 발송하거나 audit 보관 용도.
 *
 * 실 운영 구현 옵션:
 *  - iText 7 (가장 보편)
 *  - Apache PDFBox (LGPL — 상업 적용 주의)
 *  - 외부 서비스 (DocRaptor / PDFShift) — HTML → PDF 변환
 *  - headless Chromium (Puppeteer / Playwright via container)
 *
 * 본 인터페이스는 구현체에 종속되지 않도록 도메인 → byte[] 변환만 노출.
 */
interface InvoicePdfRenderer {

    /**
     * @param invoice 렌더링할 청구서
     * @return PDF binary (application/pdf)
     */
    fun render(invoice: Invoice): ByteArray
}
