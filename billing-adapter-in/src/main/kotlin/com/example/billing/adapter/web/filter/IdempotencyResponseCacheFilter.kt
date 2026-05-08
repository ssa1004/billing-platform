package com.example.billing.adapter.web.filter

import com.example.billing.application.port.out.IdempotencyKeyStore
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpMethod
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.util.ContentCachingResponseWrapper

/**
 * Stripe / 토스페이먼츠 식 *Idempotency-Key 응답 캐싱* 필터.
 *
 * <p><b>왜 필요</b>: 클라이언트가 결제 요청을 보낸 뒤 응답이 오기 전에 timeout 되어 같은 키로
 * 재시도하면, 서버는 (a) 처음 요청을 이미 처리했음에도 (b) 두 번째 요청을 다시 처리하거나
 * (c) 단순히 409 만 돌려줍니다. (a)+(c) 조합이면 클라이언트는 처리됐는지 안 됐는지 모릅니다.
 * Stripe API 표준은 *처음 응답 그대로* 24h 동안 반환합니다 — 두 번째 요청도 첫 번째와 동일한
 * 본문을 받아 정합. 토스페이먼츠 / 네이버페이 / iamport 모두 같은 패턴.</p>
 *
 * <p><b>흐름</b>:</p>
 * <ol>
 *   <li>요청에 {@code Idempotency-Key} 헤더 있고 critical endpoint (POST /api/v1/payments,
 *       /api/v1/refunds) 이면 활성.</li>
 *   <li>캐시 hit → 그 응답 (status + body) 그대로 client 에 reply, chain 차단.</li>
 *   <li>캐시 miss → 정상 처리. 응답을 wrapper 로 캡처 → 처리 성공 시 캐시.</li>
 *   <li>예외 시 cache 안 함 — rollback 흐름에서 IdempotentExecution 이 점유 lock 도 풀어주므로
 *       client 가 같은 키로 재시도 가능.</li>
 * </ol>
 *
 * <p><b>16KB cap</b>: {@link IdempotencyKeyStore#MAX_BODY_BYTES} 보다 큰 응답은 cache skip
 * (정상 처리/응답은 그대로). PDF / CSV 같은 streaming 응답은 캐시 못 함 — Stripe 도 동일.</p>
 *
 * <p><b>local dev (redis-enabled=false) 에서도 동작</b>: in-memory store 가 같은 인터페이스
 * 구현. 다만 인스턴스 재시작 시 캐시 사라짐 — local 에서만 의미.</p>
 *
 * <p><b>활성 조건</b>: {@code billing.idempotency.response-cache-enabled=true}.
 * 기본값 true — 점유 lock 만으로는 client 정합이 깨질 수 있어 일반 운영에선 항상 켜둡니다.</p>
 *
 * <p>ADR-0024 참고.</p>
 */
@Component
@ConditionalOnProperty(
    name = ["billing.idempotency.response-cache-enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
class IdempotencyResponseCacheFilter(
    private val store: IdempotencyKeyStore,
) : OncePerRequestFilter() {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 응답 캐싱 대상 경로 — POST /api/v1/payments, POST /api/v1/refunds.
     * 다른 도메인 (wallet / invoice 조회 등) 은 멱등이 자체 의미라 cache 불필요.
     */
    @Value("\${billing.idempotency.cached-paths:/api/v1/payments,/api/v1/refunds}")
    private lateinit var cachedPathsCsv: String

    private val cachedPaths: List<String> by lazy {
        cachedPathsCsv.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    }

    override fun doFilterInternal(req: HttpServletRequest, res: HttpServletResponse, chain: FilterChain) {
        val key = req.getHeader(HEADER)
        if (!shouldCache(req, key)) {
            chain.doFilter(req, res)
            return
        }
        val k = key!!

        // 1. 캐시 hit — 처음 응답 그대로 반환.
        val cached = store.findCachedResponse(k)
        if (cached.isPresent) {
            val c = cached.get()
            log.info("idempotency cache hit key={} status={}", k, c.status())
            res.status = c.status()
            res.contentType = "application/json"
            res.setHeader(REPLAY_HEADER, "true")
            res.writer.write(c.body() ?: "")
            res.writer.flush()
            return
        }

        // 2. 캐시 miss — 정상 처리 + 응답 캡처.
        val wrapper = ContentCachingResponseWrapper(res)
        var ok = false
        try {
            chain.doFilter(req, wrapper)
            ok = true
        } finally {
            // 3. 성공 응답 (2xx, 201) 만 캐시. 4xx / 5xx 는 client 가 retry 할 수 있어야 함.
            //    예외 발생 시 wrapper.copyBodyToResponse() 는 finally 에서 *반드시* 호출 — 본문 누락 방지.
            try {
                if (ok && isSuccess(wrapper.status)) {
                    val body = wrapper.contentAsByteArray
                    if (body.size <= IdempotencyKeyStore.MAX_BODY_BYTES) {
                        val text = String(body, Charsets.UTF_8)
                        store.cacheResponse(k, wrapper.status, text)
                        log.debug("idempotency cache stored key={} status={} bytes={}",
                            k, wrapper.status, body.size)
                    } else {
                        // 16KB 초과 — cache skip. 다음 retry 도 정상 처리 path 를 탄다.
                        log.warn("idempotency response too large, skip cache key={} bytes={}",
                            k, body.size)
                    }
                }
            } finally {
                wrapper.copyBodyToResponse()
            }
        }
    }

    private fun shouldCache(req: HttpServletRequest, key: String?): Boolean {
        if (key.isNullOrBlank()) return false
        if (req.method != HttpMethod.POST.name()) return false
        val path = req.requestURI ?: return false
        return cachedPaths.any { path == it || path.startsWith("$it/") }
    }

    private fun isSuccess(status: Int): Boolean = status in 200..299

    companion object {
        const val HEADER = "Idempotency-Key"
        /** 캐시 hit 시 client 가 replay 임을 알 수 있도록 응답 헤더로 표시 (Stripe 와 동일 헤더 명). */
        const val REPLAY_HEADER = "Idempotent-Replayed"
    }
}
