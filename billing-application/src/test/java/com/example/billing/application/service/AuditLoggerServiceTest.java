package com.example.billing.application.service;

import com.example.billing.application.port.out.AuditEntryRepository;
import com.example.billing.domain.audit.AuditAction;
import com.example.billing.domain.audit.AuditActor;
import com.example.billing.domain.audit.AuditEntry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AuditLoggerServiceTest {

    private static final Instant NOW = Instant.parse("2026-05-04T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Mock AuditEntryRepository repo;

    AuditLoggerService service;

    @BeforeEach
    void setUp() {
        service = new AuditLoggerService(repo, CLOCK);
        MDC.clear();
    }

    @AfterEach
    void cleanMdc() {
        MDC.clear();
    }

    @Test
    void log_persistsEntryWithGivenFields() {
        var actor = AuditActor.operator("alice", "10.0.0.1", "Chrome/123");
        service.log(actor, AuditAction.REFUND_APPROVED, "Refund", "r-1",
                null, "{\"amount\":1000}", "customer requested");

        ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(repo).save(captor.capture());
        AuditEntry saved = captor.getValue();
        assertThat(saved.actor()).isEqualTo(actor);
        assertThat(saved.action()).isEqualTo(AuditAction.REFUND_APPROVED);
        assertThat(saved.targetType()).isEqualTo("Refund");
        assertThat(saved.targetId()).isEqualTo("r-1");
        assertThat(saved.afterJson()).isEqualTo("{\"amount\":1000}");
        assertThat(saved.reason()).isEqualTo("customer requested");
        assertThat(saved.occurredAt()).isEqualTo(NOW);
        assertThat(saved.traceId()).isNull();   // MDC 비어있음
    }

    @Test
    void log_capturesTraceIdFromMdc() {
        MDC.put("traceId", "abc-123");

        service.log(AuditActor.system("svc"), AuditAction.CREDIT_GRANTED, "Credit", "c-1",
                null, "{}", null);

        ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(repo).save(captor.capture());
        assertThat(captor.getValue().traceId()).isEqualTo("abc-123");
    }

    @Test
    void log_emptyMdc_traceIdNull_butAuditStillSaved() {
        // MDC 키 없음 (tracing 비활성 환경)
        service.log(AuditActor.system("svc"), AuditAction.CREDIT_GRANTED, "Credit", "c-1",
                null, "{}", null);

        ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(repo).save(captor.capture());
        assertThat(captor.getValue().traceId()).isNull();
    }
}
