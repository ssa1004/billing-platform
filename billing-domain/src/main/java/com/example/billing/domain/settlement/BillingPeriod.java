package com.example.billing.domain.settlement;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Objects;

/**
 * 청구 기간 — 월 단위.
 *
 * <p>UTC 기준 [from, to) 반열린 구간. {@code 2026-05} → {@code 2026-05-01T00:00Z} 부터
 * {@code 2026-06-01T00:00Z} 직전까지.</p>
 *
 * <p>로컬 timezone 단위 청구 (예: KST 자정 기준) 가 필요하면 별도 {@code LocalBillingPeriod}
 * 를 도입할 수 있다. 현재는 UTC 단일.</p>
 */
public final class BillingPeriod implements Comparable<BillingPeriod> {

    private final YearMonth yearMonth;

    private BillingPeriod(YearMonth yearMonth) {
        this.yearMonth = Objects.requireNonNull(yearMonth, "yearMonth");
    }

    public static BillingPeriod of(int year, int month) {
        return new BillingPeriod(YearMonth.of(year, month));
    }

    public static BillingPeriod of(YearMonth yearMonth) {
        return new BillingPeriod(yearMonth);
    }

    /** 주어진 시각이 속한 월의 BillingPeriod. */
    public static BillingPeriod containing(Instant instant) {
        return new BillingPeriod(YearMonth.from(instant.atOffset(ZoneOffset.UTC)));
    }

    public YearMonth yearMonth() { return yearMonth; }

    public Instant fromInclusive() {
        return yearMonth.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    public Instant toExclusive() {
        return yearMonth.plusMonths(1).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    public boolean contains(Instant instant) {
        return !instant.isBefore(fromInclusive()) && instant.isBefore(toExclusive());
    }

    /** "2026-05" 같은 키. 정산 advisory lock key 에 쓰임. */
    public String toKey() {
        return yearMonth.toString();
    }

    public BillingPeriod previous() {
        return new BillingPeriod(yearMonth.minusMonths(1));
    }

    public BillingPeriod next() {
        return new BillingPeriod(yearMonth.plusMonths(1));
    }

    @Override public int compareTo(BillingPeriod other) {
        return yearMonth.compareTo(other.yearMonth);
    }

    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof BillingPeriod other)) return false;
        return yearMonth.equals(other.yearMonth);
    }

    @Override public int hashCode() { return yearMonth.hashCode(); }

    @Override public String toString() { return toKey(); }
}
