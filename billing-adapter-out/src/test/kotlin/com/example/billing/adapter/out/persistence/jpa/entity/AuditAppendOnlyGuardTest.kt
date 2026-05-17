package com.example.billing.adapter.out.persistence.jpa.entity

import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class AuditAppendOnlyGuardTest {

    private val guard = AuditAppendOnlyGuard()
    private val dummy = Any()

    @Test
    fun preUpdate_throws_blocksMutation() {
        assertThatThrownBy { guard.onPreUpdate(dummy) }
            .isInstanceOf(UnsupportedOperationException::class.java)
            .hasMessageContaining("append-only")
    }

    @Test
    fun preRemove_throws_blocksDeletion() {
        assertThatThrownBy { guard.onPreRemove(dummy) }
            .isInstanceOf(UnsupportedOperationException::class.java)
            .hasMessageContaining("append-only")
    }
}
