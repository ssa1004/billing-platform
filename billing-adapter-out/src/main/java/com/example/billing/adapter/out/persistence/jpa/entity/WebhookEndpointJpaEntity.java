package com.example.billing.adapter.out.persistence.jpa.entity;

import com.example.billing.domain.webhook.WebhookEndpointStatus;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "webhook_endpoints")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PUBLIC)
@AllArgsConstructor
public class WebhookEndpointJpaEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "customer_id", nullable = false, length = 64)
    private String customerId;

    @Column(name = "url", nullable = false, length = 2048)
    private String url;

    @Column(name = "secret", nullable = false, length = 128)
    private String secret;

    /** 구독 이벤트 타입의 JSON 배열 직렬화. mapper 가 Set<String> 로 변환. */
    @Column(name = "subscribed_event_types_json", nullable = false, columnDefinition = "TEXT")
    private String subscribedEventTypesJson;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private WebhookEndpointStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;
}
