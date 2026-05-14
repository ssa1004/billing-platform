package com.example.billing.domain.budget

/**
 * BudgetAlertRule 라이프사이클.
 *
 * ```
 *   ACTIVE ──pause()──▶ PAUSED
 *      ▲                    │
 *      └────resume()────────┘
 * ```
 *
 * 삭제 (DELETED) 는 별도 status 가 아니라 row 삭제로 처리. audit 가 필요해지면 도입.
 */
enum class BudgetAlertStatus {
    ACTIVE,
    PAUSED,
}
