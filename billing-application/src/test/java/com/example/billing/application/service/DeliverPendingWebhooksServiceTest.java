package com.example.billing.application.service;

import com.example.billing.application.port.out.WebhookDeliveryRepository;
import com.example.billing.application.port.out.WebhookEndpointRepository;
import com.example.billing.application.port.out.WebhookHttpClient;
import com.example.billing.application.port.out.WebhookHttpClient.Outcome;
import com.example.billing.domain.shared.CustomerId;
import com.example.billing.domain.webhook.WebhookDelivery;
import com.example.billing.domain.webhook.WebhookDeliveryStatus;
import com.example.billing.domain.webhook.WebhookEndpoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeliverPendingWebhooksServiceTest {

    private static final Instant NOW = Instant.parse("2026-05-01T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final CustomerId ALICE = CustomerId.of("alice");

    @Mock WebhookDeliveryRepository deliveries;
    @Mock WebhookEndpointRepository endpoints;
    @Mock WebhookHttpClient httpClient;

    DeliverPendingWebhooksService service;

    @BeforeEach
    void setUp() {
        service = new DeliverPendingWebhooksService(deliveries, endpoints, httpClient, CLOCK);
    }

    private static WebhookEndpoint endpoint() {
        return WebhookEndpoint.register(ALICE, "https://acme.example.com/hook", Set.of(), CLOCK);
    }

    private static WebhookDelivery delivery(WebhookEndpoint endpoint) {
        return WebhookDelivery.schedule(endpoint.id(), "InvoiceIssued", "{}", CLOCK);
    }

    @Test
    void emptyClaim_returnsZero() {
        when(deliveries.claimPending(eq(NOW), anyInt())).thenReturn(List.of());
        assertThat(service.deliverBatch(10)).isZero();
    }

    @Test
    void successfulDelivery_marksSuccess() {
        WebhookEndpoint ep = endpoint();
        WebhookDelivery d = delivery(ep);
        when(deliveries.claimPending(eq(NOW), anyInt())).thenReturn(List.of(d));
        when(endpoints.findById(ep.id())).thenReturn(Optional.of(ep));
        when(httpClient.send(ep, d)).thenReturn(new Outcome.Success(200));

        int n = service.deliverBatch(10);

        assertThat(n).isEqualTo(1);
        assertThat(d.status()).isEqualTo(WebhookDeliveryStatus.SUCCESS);
        assertThat(d.lastResponseStatus()).isEqualTo(200);
        verify(deliveries).save(d);
    }

    @Test
    void retryableOutcome_movesBackToPendingWithBackoff() {
        WebhookEndpoint ep = endpoint();
        WebhookDelivery d = delivery(ep);
        when(deliveries.claimPending(eq(NOW), anyInt())).thenReturn(List.of(d));
        when(endpoints.findById(ep.id())).thenReturn(Optional.of(ep));
        when(httpClient.send(ep, d)).thenReturn(new Outcome.Retryable(503, "service unavailable"));

        service.deliverBatch(10);

        assertThat(d.status()).isEqualTo(WebhookDeliveryStatus.PENDING);
        assertThat(d.attemptCount()).isEqualTo(1);
        assertThat(d.nextAttemptAt()).isEqualTo(NOW.plus(java.time.Duration.ofMinutes(1)));
        verify(deliveries).save(d);
    }

    @Test
    void deadOutcome_movesToDeadLettered() {
        WebhookEndpoint ep = endpoint();
        WebhookDelivery d = delivery(ep);
        when(deliveries.claimPending(eq(NOW), anyInt())).thenReturn(List.of(d));
        when(endpoints.findById(ep.id())).thenReturn(Optional.of(ep));
        when(httpClient.send(ep, d)).thenReturn(new Outcome.Dead(404, "not found"));

        service.deliverBatch(10);

        assertThat(d.status()).isEqualTo(WebhookDeliveryStatus.DEAD_LETTERED);
        assertThat(d.lastResponseStatus()).isEqualTo(404);
        verify(deliveries).save(d);
    }

    @Test
    void endpointVanished_marksDeadAndProceeds() {
        WebhookEndpoint ep = endpoint();
        WebhookDelivery d = delivery(ep);
        when(deliveries.claimPending(eq(NOW), anyInt())).thenReturn(List.of(d));
        when(endpoints.findById(ep.id())).thenReturn(Optional.empty());

        int n = service.deliverBatch(10);

        assertThat(n).isEqualTo(1);
        assertThat(d.status()).isEqualTo(WebhookDeliveryStatus.DEAD_LETTERED);
        verify(deliveries).save(d);
        // HTTP call 시도조차 안 함
        verify(httpClient, org.mockito.Mockito.never()).send(any(), any());
    }

    @Test
    void multipleDeliveries_eachIndependentlyHandled() {
        WebhookEndpoint ep = endpoint();
        WebhookDelivery a = delivery(ep);
        WebhookDelivery b = delivery(ep);
        when(deliveries.claimPending(eq(NOW), anyInt())).thenReturn(List.of(a, b));
        when(endpoints.findById(ep.id())).thenReturn(Optional.of(ep));
        when(httpClient.send(ep, a)).thenReturn(new Outcome.Success(200));
        when(httpClient.send(ep, b)).thenReturn(new Outcome.Retryable(503, "x"));

        service.deliverBatch(10);

        assertThat(a.status()).isEqualTo(WebhookDeliveryStatus.SUCCESS);
        assertThat(b.status()).isEqualTo(WebhookDeliveryStatus.PENDING);
        verify(deliveries).save(a);
        verify(deliveries).save(b);
    }
}
