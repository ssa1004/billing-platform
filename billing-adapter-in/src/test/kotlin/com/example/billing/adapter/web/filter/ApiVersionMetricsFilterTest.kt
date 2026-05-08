package com.example.billing.adapter.web.filter

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import jakarta.servlet.FilterChain
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

/**
 * ApiVersionMetricsFilter 단위 테스트 (ADR-0031).
 *
 * 검증:
 *  - v1 / v2 path 를 라벨로 정확히 분리.
 *  - id segment 는 라벨에 포함되지 않음 (cardinality 폭발 방지).
 *  - api 가 아닌 path (예: /actuator) 는 카운터 증가 없음.
 */
class ApiVersionMetricsFilterTest {

    private val registry = SimpleMeterRegistry()
    private val filter = ApiVersionMetricsFilter(registry)

    @Test
    fun `v1 invoices count is incremented`() {
        val req = MockHttpServletRequest("GET", "/api/v1/invoices/abc-123")
        val res = MockHttpServletResponse()
        val chain: FilterChain = mock()

        filter.doFilter(req, res, chain)

        val counter = registry.find("api.version.usage")
            .tag("version", "v1")
            .tag("resource", "/api/v1/invoices")
            .counter()
        assertThat(counter).isNotNull
        assertThat(counter!!.count()).isEqualTo(1.0)
        verify(chain).doFilter(req, res)
    }

    @Test
    fun `v2 invoices count is incremented`() {
        val req = MockHttpServletRequest("GET", "/api/v2/invoices")
        req.queryString = "customerId=c-1"
        val res = MockHttpServletResponse()
        filter.doFilter(req, res, mock())

        val counter = registry.find("api.version.usage")
            .tag("version", "v2")
            .tag("resource", "/api/v2/invoices")
            .counter()
        assertThat(counter).isNotNull
        assertThat(counter!!.count()).isEqualTo(1.0)
    }

    @Test
    fun `non-api path does not increment any counter`() {
        val req = MockHttpServletRequest("GET", "/actuator/health")
        val res = MockHttpServletResponse()
        filter.doFilter(req, res, mock())

        assertThat(registry.find("api.version.usage").counters()).isEmpty()
    }

    @Test
    fun `parseVersionAndResource extracts only resource segment`() {
        assertThat(filter.parseVersionAndResource("/api/v1/invoices/123/pdf"))
            .isEqualTo("v1" to "/api/v1/invoices")
        assertThat(filter.parseVersionAndResource("/api/v2/invoices"))
            .isEqualTo("v2" to "/api/v2/invoices")
        assertThat(filter.parseVersionAndResource("/health"))
            .isEqualTo(null to "")
        assertThat(filter.parseVersionAndResource("/api/v3/foo"))
            .isEqualTo(null to "")
    }
}
