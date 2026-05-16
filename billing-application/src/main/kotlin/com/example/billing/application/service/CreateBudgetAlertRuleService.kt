package com.example.billing.application.service

import com.example.billing.application.command.CreateBudgetAlertRuleCommand
import com.example.billing.application.port.`in`.CreateBudgetAlertRuleUseCase
import com.example.billing.application.port.out.BudgetAlertRuleRepository
import com.example.billing.application.port.out.EventPublisher
import com.example.billing.domain.budget.BudgetAlertEvents
import com.example.billing.domain.budget.BudgetAlertRule
import com.example.billing.domain.shared.CustomerId
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

@Service
open class CreateBudgetAlertRuleService(
    private val rules: BudgetAlertRuleRepository,
    private val events: EventPublisher,
    private val idempotency: IdempotentExecution,
    private val clock: Clock,
) : CreateBudgetAlertRuleUseCase {

    @Transactional
    override fun create(command: CreateBudgetAlertRuleCommand): BudgetAlertRule {
        idempotency.acquireAndReleaseOnRollback(command.idempotencyKey)

        val rule = if (command.cooldown == null) {
            BudgetAlertRule.create(CustomerId.of(command.customerId), command.threshold, clock)
        } else {
            BudgetAlertRule.create(
                CustomerId.of(command.customerId), command.threshold, command.cooldown, clock,
            )
        }
        rules.save(rule)

        events.publish(
            BudgetAlertEvents.Created(
                rule.id, rule.customerId, rule.threshold, clock.instant(),
            ),
        )

        log.info(
            "budget alert rule created id={} customer={} threshold={}",
            rule.id, rule.customerId, rule.threshold,
        )
        return rule
    }

    companion object {
        private val log = LoggerFactory.getLogger(CreateBudgetAlertRuleService::class.java)
    }
}
