package com.example.billing.domain.credit

import com.example.billing.domain.shared.Money

/**
 * 크레딧 차감 시 잔액 부족.
 *
 * record-style accessor (`creditId()`, `requested()`, `available()`) 는 `@get:JvmName` 으로
 * Java/Kotlin 양쪽 호출자에서 그대로 호출 가능.
 */
class InsufficientCreditException(
    @get:JvmName("creditId") val creditId: CreditId,
    @get:JvmName("requested") val requested: Money,
    @get:JvmName("available") val available: Money,
) : RuntimeException("insufficient credit: id=$creditId requested=$requested available=$available")
