package com.example.billing.application.service;

import com.example.billing.application.command.PlaceOrderCommand;
import com.example.billing.application.port.out.EventPublisher;
import com.example.billing.application.port.out.IdempotencyKeyStore;
import com.example.billing.application.port.out.OrderRepository;
import com.example.billing.domain.order.Order;
import com.example.billing.domain.order.OrderEvents;
import com.example.billing.domain.order.OrderStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PlaceOrderServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-05-04T10:00:00Z"), ZoneOffset.UTC);

    @Mock OrderRepository orders;
    @Mock IdempotencyKeyStore idempotencyKeys;
    @Mock EventPublisher events;

    PlaceOrderService service;

    @BeforeEach
    void setUp() {
        service = new PlaceOrderService(orders, idempotencyKeys, events, CLOCK);
    }

    @Test
    void place_savesAndPublishesPlacedEvent() {
        var cmd = new PlaceOrderCommand(
                "key-1", "alice", Currency.getInstance("KRW"),
                List.of(new PlaceOrderCommand.OrderLine("SKU", 2, BigDecimal.valueOf(1000)))
        );

        Order result = service.place(cmd);

        assertThat(result.status()).isEqualTo(OrderStatus.CREATED);
        assertThat(result.totalAmount().amount()).isEqualByComparingTo("2000");
        verify(idempotencyKeys).acquireOrThrow("key-1");
        verify(orders).save(any(Order.class));
        verify(events).publish(any(OrderEvents.OrderPlaced.class));
    }

    @Test
    void place_throwsWhenIdempotencyKeyAlreadyUsed() {
        doThrow(new IdempotencyKeyStore.DuplicateRequestException("key-1"))
                .when(idempotencyKeys).acquireOrThrow("key-1");

        var cmd = new PlaceOrderCommand(
                "key-1", "alice", Currency.getInstance("KRW"),
                List.of(new PlaceOrderCommand.OrderLine("SKU", 1, BigDecimal.valueOf(1000)))
        );

        assertThatThrownBy(() -> service.place(cmd))
                .isInstanceOf(IdempotencyKeyStore.DuplicateRequestException.class);

        verify(orders, never()).save(any());
        verify(events, never()).publish(any());
    }
}
