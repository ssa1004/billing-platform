package com.example.billing.adapter.out.persistence.jpa.entity;

import com.example.billing.domain.credit.CreditStatus;
import com.example.billing.domain.credit.CreditType;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA persistence entity for Credit. 도메인 {@link com.example.billing.domain.credit.Credit}
 * 와 분리. 매핑은 {@link com.example.billing.adapter.out.persistence.jpa.mapper.CreditJpaMapper}.
 */
@Entity
@Table(name = "credits")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PUBLIC)
@AllArgsConstructor
public class CreditJpaEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "customer_id", nullable = false, length = 64)
    private String customerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 32)
    private CreditType type;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "granted_amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal grantedAmount;

    @Column(name = "balance", nullable = false, precision = 18, scale = 2)
    private BigDecimal balance;

    @Column(name = "valid_from", nullable = false)
    private Instant validFrom;

    @Column(name = "valid_until")
    private Instant validUntil;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private CreditStatus status;

    @Column(name = "reason", length = 256)
    private String reason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;
}
