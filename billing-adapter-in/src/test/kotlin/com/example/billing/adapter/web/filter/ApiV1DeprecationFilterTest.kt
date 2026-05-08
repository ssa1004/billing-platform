package com.example.billing.adapter.web.filter

import jakarta.servlet.FilterChain
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

/**
 * ApiV1DeprecationFilter 단위 테스트 (ADR-0031).
 */
class ApiV1DeprecationFilterTest {

    @Test
    fun `v1 request gets Deprecation Sunset and Link headers when sunset configured`() {
        val filter = ApiV1DeprecationFilter("Wed, 01 Jan 2027 00:00:00 GMT")
        val req = MockHttpServletRequest("GET", "/api/v1/invoices/abc")
        val res = MockHttpServletResponse()
        filter.doFilter(req, res, mock<FilterChain>())

        assertThat(res.getHeader("Deprecation")).isEqualTo("true")
        assertThat(res.getHeader("Sunset")).isEqualTo("Wed, 01 Jan 2027 00:00:00 GMT")
        assertThat(res.getHeader("Link"))
            .isEqualTo("</api/v2/invoices/abc>; rel=\"successor-version\"")
    }

    @Test
    fun `v1 request gets no headers when sunset is blank`() {
        val filter = ApiV1DeprecationFilter("")
        val req = MockHttpServletRequest("GET", "/api/v1/invoices/abc")
        val res = MockHttpServletResponse()
        filter.doFilter(req, res, mock<FilterChain>())

        assertThat(res.getHeader("Deprecation")).isNull()
        assertThat(res.getHeader("Sunset")).isNull()
        assertThat(res.getHeader("Link")).isNull()
    }

    @Test
    fun `v2 request never gets deprecation headers`() {
        val filter = ApiV1DeprecationFilter("Wed, 01 Jan 2027 00:00:00 GMT")
        val req = MockHttpServletRequest("GET", "/api/v2/invoices/abc")
        val res = MockHttpServletResponse()
        filter.doFilter(req, res, mock<FilterChain>())

        assertThat(res.getHeader("Deprecation")).isNull()
        assertThat(res.getHeader("Sunset")).isNull()
        assertThat(res.getHeader("Link")).isNull()
    }
}
