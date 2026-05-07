package com.example.billing.domain.audit;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuditEntryTest {

    @Test
    void record_assignsRandomId() {
        var actor = AuditActor.operator("alice", "10.0.0.1", "Mozilla/5.0");
        var e1 = AuditEntry.record(actor, AuditAction.REFUND_APPROVED, "Refund", "r-1",
                null, "{\"amount\":1000}", "customer requested", "trace-1", Instant.now());
        var e2 = AuditEntry.record(actor, AuditAction.REFUND_APPROVED, "Refund", "r-1",
                null, "{\"amount\":1000}", "customer requested", "trace-1", Instant.now());

        assertThat(e1.id()).isNotNull();
        assertThat(e1.id()).isNotEqualTo(e2.id());   // 매번 다른 id
    }

    @Test
    void allowsBeforeOrAfterNull_butNotBoth_isCallerResponsibility() {
        var actor = AuditActor.system("svc");
        // 생성 — before null
        var created = AuditEntry.record(actor, AuditAction.CREDIT_GRANTED, "Credit", "c-1",
                null, "{\"amount\":5000}", null, null, Instant.now());
        assertThat(created.beforeJson()).isNull();
        assertThat(created.afterJson()).isNotNull();

        // 삭제 — after null
        var deleted = AuditEntry.record(actor, AuditAction.CREDIT_REVOKED, "Credit", "c-1",
                "{\"amount\":5000}", null, null, null, Instant.now());
        assertThat(deleted.afterJson()).isNull();
    }

    @Test
    void rejectsBlankTargetType() {
        var actor = AuditActor.system("svc");
        assertThatThrownBy(() ->
                AuditEntry.record(actor, AuditAction.CREDIT_GRANTED, "", "c-1",
                        null, null, null, null, Instant.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("targetType");
    }

    @Test
    void rejectsBlankTargetId() {
        var actor = AuditActor.system("svc");
        assertThatThrownBy(() ->
                AuditEntry.record(actor, AuditAction.CREDIT_GRANTED, "Credit", "",
                        null, null, null, null, Instant.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("targetId");
    }

    @Test
    void actorFactories_setProperType() {
        assertThat(AuditActor.user("u-1", null, null).type()).isEqualTo(AuditActor.Type.USER);
        assertThat(AuditActor.operator("op-1", null, null).type()).isEqualTo(AuditActor.Type.OPERATOR);
        assertThat(AuditActor.system("svc").type()).isEqualTo(AuditActor.Type.SYSTEM);
        assertThat(AuditActor.external("source").type()).isEqualTo(AuditActor.Type.EXTERNAL);
    }

    @Test
    void systemAndExternalActors_haveNoIpOrUserAgent() {
        var sys = AuditActor.system("scheduler");
        assertThat(sys.ipAddress()).isNull();
        assertThat(sys.userAgent()).isNull();
    }
}
