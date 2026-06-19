package com.example.billing.domain.settlement

import com.example.billing.domain.shared.CustomerId
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * [SettlementRun] 상태 머신 단위 테스트.
 *
 * 기존 Java 테스트는 [BillingPeriod] 만 다뤘고 SettlementRun 의 상태 전이
 * (PENDING → RUNNING → COMPLETED / FAILED) 는 미커버였다. 순수 도메인
 * (Spring / Testcontainers 불필요) 이라 여기서 직접 검증.
 */
class SettlementRunTest {

    private val period = BillingPeriod.of(2026, 5)
    private val customer = CustomerId.of("acme-corp")
    private val clock = Clock.fixed(Instant.parse("2026-06-01T01:00:00Z"), ZoneOffset.UTC)

    @Test
    fun `queue 는 PENDING 상태로 시작한다`() {
        val run = SettlementRun.queue(period, customer, clock)

        assertThat(run.status).isEqualTo(SettlementStatus.PENDING)
        assertThat(run.startedAt).isNull()
        assertThat(run.finishedAt).isNull()
        assertThat(run.invoicesGenerated).isZero()
        assertThat(run.customerId()).isPresent()
        assertThat(run.customerId().get()).isEqualTo(customer)
    }

    @Test
    fun `customerId 가 null 이면 전체 고객 정산 메타 row`() {
        val run = SettlementRun.queue(period, null, clock)

        assertThat(run.customerId()).isEmpty()
    }

    @Test
    fun `start 는 PENDING 에서 RUNNING 으로 전이하고 startedAt 을 찍는다`() {
        val run = SettlementRun.queue(period, customer, clock)

        run.start(clock)

        assertThat(run.status).isEqualTo(SettlementStatus.RUNNING)
        assertThat(run.startedAt).isEqualTo(clock.instant())
    }

    @Test
    fun `PENDING 이 아닌 상태에서 start 하면 예외`() {
        val run = SettlementRun.queue(period, customer, clock)
        run.start(clock)

        assertThatThrownBy { run.start(clock) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("cannot start from status RUNNING")
    }

    @Test
    fun `recordResult 는 RUNNING 에서만 호출 가능하고 COMPLETED 로 전이`() {
        val run = SettlementRun.queue(period, customer, clock)
        run.start(clock)

        run.recordResult(invoicesGenerated = 10, paymentsAttempted = 10, paymentsSucceeded = 8, clock = clock)

        assertThat(run.status).isEqualTo(SettlementStatus.COMPLETED)
        assertThat(run.invoicesGenerated).isEqualTo(10)
        assertThat(run.paymentsAttempted).isEqualTo(10)
        assertThat(run.paymentsSucceeded).isEqualTo(8)
        assertThat(run.finishedAt).isEqualTo(clock.instant())
    }

    @Test
    fun `PENDING 에서 바로 recordResult 하면 예외`() {
        val run = SettlementRun.queue(period, customer, clock)

        assertThatThrownBy { run.recordResult(1, 1, 1, clock) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("cannot record result from status PENDING")
    }

    @Test
    fun `fail 은 실패 사유와 함께 FAILED 로 전이`() {
        val run = SettlementRun.queue(period, customer, clock)
        run.start(clock)

        run.fail("pricing plan missing", clock)

        assertThat(run.status).isEqualTo(SettlementStatus.FAILED)
        assertThat(run.failureReason).isEqualTo("pricing plan missing")
        assertThat(run.finishedAt).isEqualTo(clock.instant())
    }

    @Test
    fun `PENDING 상태에서도 fail 가능하다`() {
        val run = SettlementRun.queue(period, customer, clock)

        run.fail("never started", clock)

        assertThat(run.status).isEqualTo(SettlementStatus.FAILED)
    }

    @Test
    fun `이미 COMPLETED 된 run 은 fail 할 수 없다`() {
        val run = SettlementRun.queue(period, customer, clock)
        run.start(clock)
        run.recordResult(1, 1, 1, clock)

        assertThatThrownBy { run.fail("too late", clock) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("cannot fail completed run")
    }
}
