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
        // fingerprint 검증 (ADR-0028): 모든 케이스에서 default 는 empty (= 첫 호출), 일부 테스트가
        // override.
        whenever(store.findRequestFingerprint(any())).thenReturn(Optional.empty())
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

    // ─── ADR-0028: body fingerprint 검증 ────────────────────────────────────

    @Test
    fun `cache miss — first call records body fingerprint`() {
        val req = MockHttpServletRequest("POST", "/api/v1/payments")
        req.addHeader("Idempotency-Key", "k-fp-1")
        req.setContent("""{"amount":1000}""".toByteArray())
        val res = MockHttpServletResponse()

        val chain = FilterChainStub { _, response ->
            (response as HttpServletResponse).status = 201
            response.writer.write("""{"id":"p-1"}""")
        }

        filter.doFilter(req, res, chain)

        // 첫 호출 — 같은 키 재호출 시 비교에 사용할 fingerprint 가 박혀야 함.
        verify(store).recordRequestFingerprint(eq("k-fp-1"), any())
    }

    @Test
    fun `same key with different body throws IncompatibleRequestException`() {
        val req = MockHttpServletRequest("POST", "/api/v1/payments")
        req.addHeader("Idempotency-Key", "k-fp-2")
        req.setContent("""{"amount":2000,"orderId":"o-1"}""".toByteArray())
        val res = MockHttpServletResponse()

        // 첫 요청에서 박힌 fingerprint — 다른 body 의 fingerprint 라고 가정.
        val storedFingerprint = RequestBodyFingerprint.compute(
            """{"amount":9999,"orderId":"o-DIFFERENT"}""".toByteArray()
        )
        whenever(store.findRequestFingerprint(eq("k-fp-2"))).thenReturn(Optional.of(storedFingerprint))

        val chain = FilterChainStub { _, _ -> /* 호출되면 안 됨 — fingerprint mismatch 에서 차단 */ }

        org.assertj.core.api.Assertions.assertThatThrownBy {
            filter.doFilter(req, res, chain)
        }.isInstanceOf(com.example.billing.application.port.out.IdempotencyKeyStore.IncompatibleRequestException::class.java)

        assertThat(chain.invoked).isFalse()
        verify(store, never()).cacheResponse(any(), any(), any())
    }

    @Test
    fun `same key with same body proceeds normally — fingerprint match`() {
        val req = MockHttpServletRequest("POST", "/api/v1/payments")
        req.addHeader("Idempotency-Key", "k-fp-3")
        val bodyBytes = """{"amount":3000}""".toByteArray()
        req.setContent(bodyBytes)
        val res = MockHttpServletResponse()

        // 같은 body 의 fingerprint 가 이미 박혀 있음 — race window 에서 첫 요청이 cache 직전 단계.
        val matchingFingerprint = RequestBodyFingerprint.compute(bodyBytes)
        whenever(store.findRequestFingerprint(eq("k-fp-3"))).thenReturn(Optional.of(matchingFingerprint))
        whenever(store.findCachedResponse(eq("k-fp-3"))).thenReturn(Optional.empty())

        val chain = FilterChainStub { _, response ->
            (response as HttpServletResponse).status = 201
            response.writer.write("""{"id":"p-3"}""")
        }

        filter.doFilter(req, res, chain)

        assertThat(chain.invoked).isTrue()
        assertThat(res.status).isEqualTo(201)
    }

    @Test
    fun `controller can re-read body after fingerprint computation`() {
        // 핵심 — fingerprint 계산을 위해 우리가 inputStream 을 한 번 읽었으니, controller 가 다시
        // 읽었을 때도 같은 body 가 보여야 함.
        val req = MockHttpServletRequest("POST", "/api/v1/payments")
        req.addHeader("Idempotency-Key", "k-fp-4")
        val originalBody = """{"orderId":"o-99","amount":4500}"""
        req.setContent(originalBody.toByteArray())
        val res = MockHttpServletResponse()

        var bodyReadByController: String? = null
        val chain = FilterChainStub { request, response ->
            bodyReadByController = (request as jakarta.servlet.ServletRequest).reader.readText()
            (response as HttpServletResponse).status = 201
            response.writer.write("""{"id":"ok"}""")
        }

        filter.doFilter(req, res, chain)

        assertThat(bodyReadByController).isEqualTo(originalBody)
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
