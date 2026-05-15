package com.example.billing.application.service;

import com.example.billing.application.command.GrantCreditCommand;
import com.example.billing.application.port.in.AuditLogger;
import com.example.billing.application.port.in.GrantCreditUseCase;
import com.example.billing.application.port.out.CreditRepository;
import com.example.billing.application.port.out.EventPublisher;
import com.example.billing.domain.audit.AuditAction;
import com.example.billing.domain.audit.AuditActor;
import com.example.billing.domain.credit.Credit;
import com.example.billing.domain.credit.CreditEvents;
import com.example.billing.domain.shared.CustomerId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

/**
 * Credit 발급 use case.
 *
 * <p>운영자 / CS / 마케팅 시스템이 호출. 발급은 단순히 잔액 +X 가 아니라 발급 사유 와
 * 유효 기간 을 함께 기록 — 회계 / 만료 / 회수 모두에 영향.</p>
 *
 * <p>idempotency 는 호출 측 (Idempotency-Key 헤더) 책임. 같은 customer 에게 같은 사유로
 * 중복 발급되는 것은 기능적으로 가능 (CS 가 두 번 보상해주는 케이스 등) 이므로 도메인에서
 * 일부러 막지 않는다.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GrantCreditService implements GrantCreditUseCase {

    private final CreditRepository credits;
    private final EventPublisher events;
    private final IdempotentExecution idempotency;
    private final AuditLogger audit;
    private final Clock clock;

    @Override
    @Transactional
    public Credit grant(GrantCreditCommand cmd) {
        idempotency.acquireAndReleaseOnRollback(cmd.idempotencyKey());
        Credit credit = Credit.grant(
                CustomerId.of(cmd.customerId()),
                cmd.type(),
                cmd.amount(),
                cmd.validFrom(),
                cmd.validUntil(),
                cmd.reason(),
                clock
        );
        credits.save(credit);

        events.publish(new CreditEvents.CreditGranted(
                credit.id(), credit.customerId(), credit.type(),
                credit.grantedAmount(), credit.validFrom(), credit.validUntil(),
                clock.instant()
        ));

        // Audit — "누가 / 누구에게 / 얼마를 / 왜" 영구 기록 (회계 감사 / 컴플레인 응대 1차 근거).
        // actor 는 호출 진입점 (REST controller) 이 JWT 에서 채워줘야 정확. 본 service 는
        // 내부 시스템 호출 (CS 도구 / 마케팅 캠페인) 도 받으므로 default 는 SYSTEM.
        audit.log(
                AuditActor.system("credit-service"),
                AuditAction.CREDIT_GRANTED,
                "Credit",
                credit.id().toString(),
                null,                                              // before — 신규 발급이라 없음
                AuditPayloads.object()
                        .put("amount", credit.grantedAmount())
                        .put("type", credit.type())
                        // validUntil 이 null 이면 JSON null 리터럴 (기존엔 "null" 문자열이었음).
                        .put("validUntil", credit.validUntil())
                        .build(),
                cmd.reason()
        );

        log.info("credit granted id={} customer={} type={} amount={} validUntil={}",
                credit.id(), credit.customerId(), credit.type(),
                credit.grantedAmount(), credit.validUntil());
        return credit;
    }
}
