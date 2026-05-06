package com.example.billing.application.service;

import com.example.billing.application.command.CreateBudgetAlertRuleCommand;
import com.example.billing.application.port.in.CreateBudgetAlertRuleUseCase;
import com.example.billing.application.port.out.BudgetAlertRuleRepository;
import com.example.billing.application.port.out.EventPublisher;
import com.example.billing.application.port.out.IdempotencyKeyStore;
import com.example.billing.domain.budget.BudgetAlertEvents;
import com.example.billing.domain.budget.BudgetAlertRule;
import com.example.billing.domain.shared.CustomerId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

@Service
@RequiredArgsConstructor
@Slf4j
public class CreateBudgetAlertRuleService implements CreateBudgetAlertRuleUseCase {

    private final BudgetAlertRuleRepository rules;
    private final EventPublisher events;
    private final IdempotencyKeyStore idempotencyKeys;
    private final Clock clock;

    @Override
    @Transactional
    public BudgetAlertRule create(CreateBudgetAlertRuleCommand cmd) {
        idempotencyKeys.acquireOrThrow(cmd.idempotencyKey());

        BudgetAlertRule rule = cmd.cooldown() == null
                ? BudgetAlertRule.create(CustomerId.of(cmd.customerId()), cmd.threshold(), clock)
                : BudgetAlertRule.create(CustomerId.of(cmd.customerId()), cmd.threshold(), cmd.cooldown(), clock);
        rules.save(rule);

        events.publish(new BudgetAlertEvents.Created(
                rule.id(), rule.customerId(), rule.threshold(), clock.instant()));

        log.info("budget alert rule created id={} customer={} threshold={}",
                rule.id(), rule.customerId(), rule.threshold());
        return rule;
    }
}
