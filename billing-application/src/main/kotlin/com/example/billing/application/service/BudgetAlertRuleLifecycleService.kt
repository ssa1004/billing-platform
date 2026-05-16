package com.example.billing.application.service

import com.example.billing.application.exception.BudgetAlertRuleNotFoundException
import com.example.billing.application.port.`in`.BudgetAlertRuleLifecycleUseCase
import com.example.billing.application.port.out.BudgetAlertRuleRepository
import com.example.billing.application.port.out.EventPublisher
import com.example.billing.domain.budget.BudgetAlertRuleId
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

@Service
open class BudgetAlertRuleLifecycleService(
    private val rules: BudgetAlertRuleRepository,
    private val events: EventPublisher,
    private val clock: Clock,
) : BudgetAlertRuleLifecycleUseCase {

    @Transactional
    override fun pause(ruleId: BudgetAlertRuleId) {
        val rule = rules.findById(ruleId)
            .orElseThrow { BudgetAlertRuleNotFoundException(ruleId) }
        val ev = rule.pause(clock)
        rules.save(rule)
        events.publish(ev)
        log.info("budget alert rule paused id={}", ruleId)
    }

    @Transactional
    override fun resume(ruleId: BudgetAlertRuleId) {
        val rule = rules.findById(ruleId)
            .orElseThrow { BudgetAlertRuleNotFoundException(ruleId) }
        val ev = rule.resume(clock)
        rules.save(rule)
        events.publish(ev)
        log.info("budget alert rule resumed id={}", ruleId)
    }

    companion object {
        private val log = LoggerFactory.getLogger(BudgetAlertRuleLifecycleService::class.java)
    }
}
