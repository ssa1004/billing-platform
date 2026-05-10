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
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Aged receivables 분석 — 미수 invoice 를 (customer × currency) 별 + aging bucket 별로 집계.
 *
 * <p>회계 / collection 팀이 보는 표 형태:
 * <pre>
 *   customer        | currency | 0-30일 | 31-60일 | 61-90일 | 90+일  | total
 *   acme-corp       | KRW      | 1,000  | 500     | 0       | 0      | 1,500
 *   widget-inc      | USD      | 0      | 200     | 800     | 3,000  | 4,000
 *   global-co       | KRW      | 100    | 0       | 0       | 0      | 100
 *   global-co       | USD      | 0      | 50      | 0       | 0      | 50
 * </pre>
 *
 * <p>90+일 이 큰 row 는 collection workflow (계정 정지, 법무 송장, 손실 처리) 대상.</p>
 *
 * <p><b>왜 (customer, currency) 로 그룹핑 하는가</b>: 한 customer 가 KRW 와 USD invoice 를
 * 동시에 갖는 다중 통화 케이스에서, 한 bucket 에 두 통화를 더하면 {@link Money#add} 가 currency
 * mismatch 로 throw → 보고서 전체가 죽습니다. 통화 단위로 row 를 분리하면 이런 사고 없이 각
 * 통화의 미수가 따로 집계됩니다 (환산은 별도 FX 도메인 책임).</p>
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
        Map<BucketKey, AgingBuckets> byCustomerCurrency = new HashMap<>();
        for (Invoice inv : unpaid) {
            BucketKey key = new BucketKey(inv.customerId(), inv.total().currency());
            byCustomerCurrency.computeIfAbsent(key, k -> new AgingBuckets(k.currency()))
                    .add(inv, now);
        }
        // amountDue 가 모두 0 인 (customer × currency) row (전 invoice 가 credit 으로 상계된
        // 경우) 는 제외 — collection 화면에 노이즈만 됨.
        Map<BucketKey, AgingBuckets> sorted = new TreeMap<>(
                Comparator.comparing((BucketKey k) -> k.customerId().value())
                        .thenComparing(k -> k.currency().getCurrencyCode()));
        byCustomerCurrency.forEach((k, v) -> {
            if (!v.total().isZero()) sorted.put(k, v);
        });
        return new Report(now, sorted);
    }

    public record Report(Instant asOf, Map<BucketKey, AgingBuckets> byCustomerCurrency) {}

    /** customer × currency 복합 키. 다중 통화 invoice 를 가진 customer 도 안전히 분리 집계. */
    public record BucketKey(CustomerId customerId, java.util.Currency currency) {
        public BucketKey {
            Objects.requireNonNull(customerId, "customerId");
            Objects.requireNonNull(currency, "currency");
        }
    }

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
