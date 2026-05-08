package com.example.billing.adapter.web.filter

import com.example.billing.application.port.out.IdempotencyKeyStore
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletResponse
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.util.Optional

/**
 * IdempotencyResponseCacheFilter 단위 테스트.
 *
 * 검증:
 *  - 헤더 없으면 무동작 (chain 통과만).
 *  - cached path 가 아니면 무동작.
 *  - 캐시 hit 면 chain 차단 + 처음 응답 그대로 reply + Idempotent-Replayed 헤더.
 *  - 캐시 miss 면 chain 진행 + 성공 응답 (2xx) 캐싱.
 *  - 4xx / 5xx 응답은 캐싱 안 함 (client 가 retry 가능해야).
 *  - 16KB 초과 응답은 캐싱 skip.
 */
class IdempotencyResponseCacheFilterTest {

    private val store: IdempotencyKeyStore = mock()
    private lateinit var filter: IdempotencyResponseCacheFilter

    @BeforeEach
    fun setUp() {
        filter = IdempotencyResponseCacheFilter(store)
        // @Value 가 standalone 환경에서 안 박히니 reflection 으로 set.
        val field = IdempotencyResponseCacheFilter::class.java.getDeclaredField("cachedPathsCsv")
        field.isAccessible = true
        field.set(filter, "/api/v1/payments,/api/v1/refunds")
    }

    @Test
    fun `no Idempotency-Key header — passes through, no cache lookup`() {
        val req = MockHttpServletRequest("POST", "/api/v1/payments")
        val res = MockHttpServletResponse()
        val chain = FilterChainStub { _, response ->
            (response as HttpServletResponse).status = 201
            response.writer.write("""{"id":"p-1"}""")
        }

        filter.doFilter(req, res, chain)

        assertThat(chain.invoked).isTrue()
        assertThat(res.status).isEqualTo(201)
        verify(store, never()).findCachedResponse(any())
        verify(store, never()).cacheResponse(any(), any(), any())
    }

    @Test
    fun `non-cached path — passes through with header present`() {
        val req = MockHttpServletRequest("POST", "/api/v1/orders")
        req.addHeader("Idempotency-Key", "k-1")
        val res = MockHttpServletResponse()
        val chain = FilterChainStub { _, response ->
            (response as HttpServletResponse).status = 201
        }

        filter.doFilter(req, res, chain)

        assertThat(chain.invoked).isTrue()
        verify(store, never()).findCachedResponse(any())
        verify(store, never()).cacheResponse(any(), any(), any())
    }

    @Test
    fun `cache hit — chain blocked, original response returned with Idempotent-Replayed header`() {
        val req = MockHttpServletRequest("POST", "/api/v1/payments")
        req.addHeader("Idempotency-Key", "k-2")
        val res = MockHttpServletResponse()
        whenever(store.findCachedResponse(eq("k-2")))
            .thenReturn(Optional.of(IdempotencyKeyStore.CachedResponse(201, """{"id":"original"}""")))

        val chain = FilterChainStub { _, _ -> /* should not be invoked */ }
        filter.doFilter(req, res, chain)

        assertThat(chain.invoked).isFalse()
        assertThat(res.status).isEqualTo(201)
        assertThat(res.contentAsString).isEqualTo("""{"id":"original"}""")
        assertThat(res.getHeader("Idempotent-Replayed")).isEqualTo("true")
        verify(store, never()).cacheResponse(any(), any(), any())
    }

    @Test
    fun `cache miss — successful 2xx response gets cached`() {
        val req = MockHttpServletRequest("POST", "/api/v1/payments")
        req.addHeader("Idempotency-Key", "k-3")
        val res = MockHttpServletResponse()
        whenever(store.findCachedResponse(eq("k-3"))).thenReturn(Optional.empty())

        val chain = FilterChainStub { _, response ->
            val r = response as HttpServletResponse
            r.status = 201
            r.contentType = "application/json"
            r.writer.write("""{"id":"new-payment"}""")
        }

        filter.doFilter(req, res, chain)

        assertThat(chain.invoked).isTrue()
        assertThat(res.status).isEqualTo(201)
        verify(store).cacheResponse(eq("k-3"), eq(201), eq("""{"id":"new-payment"}"""))
    }

    @Test
    fun `cache miss — 4xx response NOT cached so client can retry`() {
        val req = MockHttpServletRequest("POST", "/api/v1/payments")
        req.addHeader("Idempotency-Key", "k-4")
        val res = MockHttpServletResponse()
        whenever(store.findCachedResponse(eq("k-4"))).thenReturn(Optional.empty())

        val chain = FilterChainStub { _, response ->
            val r = response as HttpServletResponse
            r.status = 422
            r.writer.write("""{"code":"VALIDATION"}""")
        }

        filter.doFilter(req, res, chain)

        verify(store, never()).cacheResponse(any(), any(), any())
    }

    @Test
    fun `cache miss — 5xx response NOT cached`() {
        val req = MockHttpServletRequest("POST", "/api/v1/payments")
        req.addHeader("Idempotency-Key", "k-5")
        val res = MockHttpServletResponse()
        whenever(store.findCachedResponse(eq("k-5"))).thenReturn(Optional.empty())

        val chain = FilterChainStub { _, response ->
            val r = response as HttpServletResponse
            r.status = 500
            r.writer.write("""{"code":"INTERNAL"}""")
        }

        filter.doFilter(req, res, chain)

        verify(store, never()).cacheResponse(any(), any(), any())
    }

    @Test
    fun `body larger than 16KB — cache skipped, response still flushed`() {
        val req = MockHttpServletRequest("POST", "/api/v1/refunds")
        req.addHeader("Idempotency-Key", "k-6")
        val res = MockHttpServletResponse()
        whenever(store.findCachedResponse(eq("k-6"))).thenReturn(Optional.empty())

        val bigBody = "x".repeat(IdempotencyKeyStore.MAX_BODY_BYTES + 100)
        val chain = FilterChainStub { _, response ->
            val r = response as HttpServletResponse
            r.status = 201
            r.writer.write(bigBody)
        }

        filter.doFilter(req, res, chain)

        verify(store, never()).cacheResponse(any(), any(), any())
        // 응답 본문은 그대로 client 에게 전달됨.
        assertThat(res.contentAsString).isEqualTo(bigBody)
    }

    @Test
    fun `cache miss — chain throws, no cache write`() {
        val req = MockHttpServletRequest("POST", "/api/v1/payments")
        req.addHeader("Idempotency-Key", "k-7")
        val res = MockHttpServletResponse()
        whenever(store.findCachedResponse(eq("k-7"))).thenReturn(Optional.empty())

        val chain = FilterChainStub { _, _ -> throw RuntimeException("downstream blew up") }

        try {
            filter.doFilter(req, res, chain)
        } catch (e: RuntimeException) {
            assertThat(e.message).contains("downstream")
        }

        verify(store, never()).cacheResponse(any(), any(), any())
    }

    private class FilterChainStub(
        private val handler: (req: jakarta.servlet.ServletRequest, res: jakarta.servlet.ServletResponse) -> Unit,
    ) : FilterChain {
        var invoked = false
        override fun doFilter(request: jakarta.servlet.ServletRequest, response: jakarta.servlet.ServletResponse) {
            invoked = true
            handler(request, response)
        }
    }
}
