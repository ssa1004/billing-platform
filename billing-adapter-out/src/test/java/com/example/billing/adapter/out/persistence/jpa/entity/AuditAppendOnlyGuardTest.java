package com.example.billing.adapter.out.persistence.jpa.entity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuditAppendOnlyGuardTest {

    private final AuditAppendOnlyGuard guard = new AuditAppendOnlyGuard();
    private final Object dummy = new Object();

    @Test
    void preUpdate_throws_blocksMutation() {
        assertThatThrownBy(() -> guard.onPreUpdate(dummy))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("append-only");
    }

    @Test
    void preRemove_throws_blocksDeletion() {
        assertThatThrownBy(() -> guard.onPreRemove(dummy))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("append-only");
    }
}
