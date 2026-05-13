package com.example.billing.domain.settlement

import java.time.Instant
import java.time.YearMonth
import java.time.ZoneOffset

/**
 * 청구 기간 — 월 단위.
 *
 * UTC 기준 `[from, to)` 반열린 구간. `2026-05` → `2026-05-01T00:00Z` 부터
 * `2026-06-01T00:00Z` 직전까지.
 *
 * 로컬 timezone 단위 청구 (예: KST 자정 기준) 가 필요하면 별도 `LocalBillingPeriod`
 * 를 도입할 수 있다. 현재는 UTC 단일.
 *
 * Java 호환 — record-style accessor (yearMonth() 등) 보존. private 생성자 + companion
 * factory 패턴은 Java 의 `private BillingPeriod(YearMonth)` 와 동일 (단 Kotlin 의
 * data class 는 `copy()` 가 컴파일러가 생성하므로 외부에서 사본을 만들 수는 있으나,
 * 본 도메인은 monthYear 가 곧 동일성이므로 의도된 동작).
 */
@ConsistentCopyVisibility
data class BillingPeriod private constructor(
    @get:JvmName("yearMonth") val yearMonth: YearMonth,
) : Comparable<BillingPeriod> {

    fun fromInclusive(): Instant =
        yearMonth.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant()

    fun toExclusive(): Instant =
        yearMonth.plusMonths(1).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant()

    fun contains(instant: Instant): Boolean =
        !instant.isBefore(fromInclusive()) && instant.isBefore(toExclusive())

    /** "2026-05" 같은 키. 정산 advisory lock key 에 쓰임. */
    fun toKey(): String = yearMonth.toString()

    fun previous(): BillingPeriod = BillingPeriod(yearMonth.minusMonths(1))

    fun next(): BillingPeriod = BillingPeriod(yearMonth.plusMonths(1))

    override fun compareTo(other: BillingPeriod): Int = yearMonth.compareTo(other.yearMonth)

    override fun toString(): String = toKey()

    companion object {
        @JvmStatic
        fun of(year: Int, month: Int): BillingPeriod = BillingPeriod(YearMonth.of(year, month))

        @JvmStatic
        fun of(yearMonth: YearMonth): BillingPeriod = BillingPeriod(yearMonth)

        /** 주어진 시각이 속한 월의 BillingPeriod. */
        @JvmStatic
        fun containing(instant: Instant): BillingPeriod =
            BillingPeriod(YearMonth.from(instant.atOffset(ZoneOffset.UTC)))
    }
}
