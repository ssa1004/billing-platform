package com.example.billing.application.dto;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link DlqMessageFilter} 의 source / topic / consumerGroup 해석 로직 단위 테스트.
 */
class DlqMessageFilterTest {

    @Test
    void resolvedSource_returnsExplicitSource_whenSet() {
        DlqMessageFilter f = new DlqMessageFilter(
                DlqSource.REFUND, "billing.payment.captured", null, null, null, null);
        assertThat(f.resolvedSource()).isEqualTo(DlqSource.REFUND);
    }

    @Test
    void resolvedSource_derivesFromTopic_whenSourceNull() {
        DlqMessageFilter f = new DlqMessageFilter(
                null, "billing.refund.failed", null, null, null, null);
        assertThat(f.resolvedSource()).isEqualTo(DlqSource.REFUND);
    }

    @Test
    void resolvedSource_returnsNull_whenTopicHasUnknownPrefix() {
        DlqMessageFilter f = new DlqMessageFilter(
                null, "other.system.event", null, null, null, null);
        assertThat(f.resolvedSource()).isNull();
    }

    @Test
    void resolvedSource_returnsNull_whenBothEmpty() {
        assertThat(DlqMessageFilter.EMPTY.resolvedSource()).isNull();
    }

    @Test
    void isUnknownConsumerGroup_trueForNonBillingPrefix() {
        DlqMessageFilter f = new DlqMessageFilter(
                null, null, "notification-hub-push", null, null, null);
        assertThat(f.isUnknownConsumerGroup()).isTrue();
    }

    @Test
    void isUnknownConsumerGroup_falseForBillingPrefix() {
        DlqMessageFilter f = new DlqMessageFilter(
                null, null, "billing-payment", null, null, null);
        assertThat(f.isUnknownConsumerGroup()).isFalse();
    }

    @Test
    void isUnknownConsumerGroup_falseWhenBlank() {
        assertThat(DlqMessageFilter.EMPTY.isUnknownConsumerGroup()).isFalse();
    }
}
