package com.example.billing.application.service;

import com.example.billing.application.port.out.InvoiceRepository;
import com.example.billing.domain.invoice.Invoice;
import com.example.billing.domain.shared.CustomerId;
import com.example.billing.domain.shared.Money;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Aged receivables 분석 — 미수 invoice 를 customer 별 + aging bucket 별로 집계.
 *
 * <p>회계 / collection 팀이 보는 표 형태:
 * <pre>
 *   customer        | 0-30일 | 31-60일 | 61-90일 | 90+일  | total
 *   acme-corp       | 1,000  | 500     | 0       | 0      | 1,500
 *   widget-inc      | 0      | 200     | 800     | 3,000  | 4,000
 * </pre>
 *
 * <p>90+일 이 큰 customer 는 collection workflow (계정 정지, 법무 송장, 손실 처리) 대상.</p>
 */
@Service
public class AgedReceivablesService {

    private static final int FETCH_LIMIT = 10_000;

    private final InvoiceRepository invoiceRepository;
    private final Clock clock;

    public AgedReceivablesService(InvoiceRepository invoiceRepository, Clock clock) {
        this.invoiceRepository = invoiceRepository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Report report() {
        Instant now = clock.instant();
        List<Invoice> unpaid = invoiceRepository.findUnpaid(now, FETCH_LIMIT);
        Map<CustomerId, AgingBuckets> byCustomer = new HashMap<>();
        for (Invoice inv : unpaid) {
            byCustomer.computeIfAbsent(inv.customerId(), k -> new AgingBuckets(inv.total().currency()))
                    .add(inv, now);
        }
        Map<String, AgingBuckets> sorted = new TreeMap<>();
        // amountDue 가 모두 0 인 customer (전 invoice 가 credit 으로 상계된 경우) 는 제외 —
        // collection 화면에 노이즈만 됨.
        byCustomer.forEach((k, v) -> {
            if (!v.total().isZero()) sorted.put(k.value(), v);
        });
        return new Report(now, sorted);
    }

    public record Report(Instant asOf, Map<String, AgingBuckets> byCustomer) {}

    public static final class AgingBuckets {
        private Money current;     // 0-30 일
        private Money over30;
        private Money over60;
        private Money over90;
        private final java.util.Currency currency;

        AgingBuckets(java.util.Currency currency) {
            this.currency = currency;
            this.current = Money.zero(currency);
            this.over30 = Money.zero(currency);
            this.over60 = Money.zero(currency);
            this.over90 = Money.zero(currency);
        }

        void add(Invoice inv, Instant now) {
            Instant due = inv.dueAt() != null ? inv.dueAt() : inv.createdAt();
            long days = ChronoUnit.DAYS.between(due, now);
            // amountDue = total - appliedCredit. credit 이 일부 적용된 invoice 는 이미 그만큼
            // 받은 셈이라 receivable 에서 빼고 잡아야 한다. total() 을 쓰면 collection 팀이
            // 보는 미수 금액이 부풀려져 잘못된 액션 (계정 정지 / 법무 송장) 으로 이어진다.
            Money amount = inv.amountDue();
            if (amount.isZero()) return;   // 전액 credit 으로 상계된 invoice 는 표에 안 잡음
            if (days <= 30) current = current.add(amount);
            else if (days <= 60) over30 = over30.add(amount);
            else if (days <= 90) over60 = over60.add(amount);
            else over90 = over90.add(amount);
        }

        public Money current() { return current; }
        public Money over30() { return over30; }
        public Money over60() { return over60; }
        public Money over90() { return over90; }
        public Money total() { return current.add(over30).add(over60).add(over90); }
        public java.util.Currency currency() { return currency; }
    }
}
