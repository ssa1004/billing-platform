package com.example.billing.domain.budget;

import java.util.Objects;
import java.util.UUID;

public record BudgetAlertRuleId(UUID value) {
    public BudgetAlertRuleId { Objects.requireNonNull(value, "BudgetAlertRuleId.value"); }
    public static BudgetAlertRuleId newId() { return new BudgetAlertRuleId(UUID.randomUUID()); }
    public static BudgetAlertRuleId of(String s) { return new BudgetAlertRuleId(UUID.fromString(s)); }
    @Override public String toString() { return value.toString(); }
}
