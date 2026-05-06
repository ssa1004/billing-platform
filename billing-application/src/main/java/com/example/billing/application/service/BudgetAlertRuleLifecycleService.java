package com.example.billing.application.service;

import com.example.billing.application.exception.BudgetAlertRuleNotFoundException;
import com.example.billing.application.port.in.BudgetAlertRuleLifecycleUseCase;
import com.example.billing.application.port.out.BudgetAlertRuleRepository;
import com.example.billing.application.port.out.EventPublisher;
import com.example.billing.domain.budget.BudgetAlertRule;
import com.example.billing.domain.budget.BudgetAlertRuleId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

@Service
@RequiredArgsConstructor
@Slf4j
public class BudgetAlertRuleLifecycleService implements BudgetAlertRuleLifecycleUseCase {

    private final BudgetAlertRuleRepository rules;
    private final EventPublisher events;
    private final Clock clock;

    @Override
    @Transactional
    public void pause(BudgetAlertRuleId ruleId) {
        BudgetAlertRule rule = rules.findById(ruleId)
                .orElseThrow(() -> new BudgetAlertRuleNotFoundException(ruleId));
        var ev = rule.pause(clock);
        rules.save(rule);
        events.publish(ev);
        log.info("budget alert rule paused id={}", ruleId);
    }

    @Override
    @Transactional
    public void resume(BudgetAlertRuleId ruleId) {
        BudgetAlertRule rule = rules.findById(ruleId)
                .orElseThrow(() -> new BudgetAlertRuleNotFoundException(ruleId));
        var ev = rule.resume(clock);
        rules.save(rule);
        events.publish(ev);
        log.info("budget alert rule resumed id={}", ruleId);
    }
}
