package com.example.billing.adapter.web.filter

import com.example.billing.application.port.out.IdempotencyKeyStore
import jakarta.servlet.FilterChain
import jakarta.servlet.ReadListener
import jakarta.servlet.ServletInputStream
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
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
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStreamReader

/**
 * Idempotency-Key 응답 캐싱 + body fingerprint 검증 필터 (결제 API 표준 패턴).
 *
 * <p><b>왜 필요</b>: 클라이언트가 결제 요청을 보낸 뒤 응답이 오기 전에 timeout 되어 같은 키로
 * 재시도하면, 서버는 (a) 처음 요청을 이미 처리했음에도 (b) 두 번째 요청을 다시 처리하거나
 * (c) 단순히 409 만 돌려줍니다. (a)+(c) 조합이면 클라이언트는 처리됐는지 안 됐는지 모릅니다.
 * 결제 API 의 표준 처방 — 대표 출처로 Stripe API 의 Idempotency-Key 명세 — 은 *처음 응답
 * 그대로* 24h 동안 반환합니다. 두 번째 요청도 첫 번째와 동일한 본문을 받아 정합.</p>
 *
 * <p><b>흐름</b>:</p>
 * <ol>
 *   <li>요청에 {@code Idempotency-Key} 헤더 있고 critical endpoint (POST /api/v1/payments,
 *       /api/v1/refunds) 이면 활성.</li>
 *   <li>request body 의 SHA-256 fingerprint 계산.</li>
 *   <li>저장된 fingerprint 가 있고 *다른 값* 이면 422 INCOMPATIBLE_PARAMS — client bug 검출
 *       (같은 멱등 키로 다른 body, 잠재적 결제 사고).</li>
 *   <li>캐시 hit → 그 응답 (status + body) 그대로 client 에 reply, chain 차단.</li>
 *   <li>캐시 miss → fingerprint 박고 정상 처리. 응답을 wrapper 로 캡처 → 처리 성공 시 캐시.</li>
 *   <li>예외 시 cache 안 함 — rollback 흐름에서 IdempotentExecution 이 점유 lock + fingerprint 도
 *       풀어주므로 client 가 같은 키로 재시도 가능.</li>
 * </ol>
 *
 * <p><b>16KB cap</b>: {@link IdempotencyKeyStore#MAX_BODY_BYTES} 보다 큰 응답은 cache skip
 * (정상 처리/응답은 그대로). PDF / CSV 같은 streaming 응답은 캐시 못 함.</p>
 *
 * <p><b>1MB body cap (fingerprint)</b>: {@link IdempotencyKeyStore#MAX_FINGERPRINT_BODY_BYTES} 초과
 * 본문은 fingerprint skip. 정상 결제 요청 < 4KB 라 운영 영향 없음. 거대한 multipart 업로드 등 회피.</p>
 *
 * <p><b>local dev (redis-enabled=false) 에서도 동작</b>: in-memory store 가 같은 인터페이스
 * 구현. 다만 인스턴스 재시작 시 캐시 사라짐 — local 에서만 의미.</p>
 *
 * <p><b>활성 조건</b>: {@code billing.idempotency.response-cache-enabled=true}.
 * 기본값 true — 점유 lock 만으로는 client 정합이 깨질 수 있어 일반 운영에선 항상 켜둡니다.</p>
 *
 * <p>ADR-0024, ADR-0028 참고.</p>
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

        // request body 를 두 번 읽기 위해 자체 wrapper. ContentCachingRequestWrapper 는 *chain
        // downstream* 의 read 만 캡처 — 우리는 chain 전에 fingerprint 가 필요해서 직접 buffer 한 뒤
        // controller 에는 같은 byte[] 를 다시 노출하는 wrapper 를 넘김.
        val bodyBytes = req.inputStream.use { it.readAllBytes() }
        val fingerprint = if (bodyBytes.size > IdempotencyKeyStore.MAX_FINGERPRINT_BODY_BYTES) {
            // 1MB 초과 — fingerprint 비교 skip. 같은 키 재시도 시 응답 캐시만으로 정합 보장.
            log.warn("idempotency request body too large, skip fingerprint key={} bytes={}",
                k, bodyBytes.size)
            null
        } else {
            RequestBodyFingerprint.compute(bodyBytes)
        }

        // 1. 같은 키로 *다른 body* 가 들어왔는지 검출 — 422 INCOMPATIBLE_PARAMS.
        if (fingerprint != null) {
            val existing = store.findRequestFingerprint(k)
            if (existing.isPresent && !RequestBodyFingerprint.matches(existing.get(), fingerprint)) {
                log.warn("idempotency key reused with different body key={}", k)
                throw IdempotencyKeyStore.IncompatibleRequestException(k)
            }
        }

        // 2. 캐시 hit — 처음 응답 그대로 반환.
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

        // 3. 첫 처리 — fingerprint 박음. (응답 cache 가 박히기 전에라도 같은 키로 *다른 body* 가
        //    오면 422 가 떨어지도록.)
        if (fingerprint != null) {
            store.recordRequestFingerprint(k, fingerprint)
        }

        // 4. 캐시 miss — 정상 처리 + 응답 캡처. controller 는 reqWrapper 를 통해 같은 body 를 읽음.
        val resWrapper = ContentCachingResponseWrapper(res)
        val reqWrapper = CachedBodyHttpServletRequest(req, bodyBytes)
        var ok = false
        try {
            chain.doFilter(reqWrapper, resWrapper)
            ok = true
        } finally {
            // 5. 성공 응답 (2xx, 201) 만 캐시. 4xx / 5xx 는 client 가 retry 할 수 있어야 함.
            //    예외 발생 시 wrapper.copyBodyToResponse() 는 finally 에서 *반드시* 호출 — 본문 누락 방지.
            try {
                if (ok && isSuccess(resWrapper.status)) {
                    val body = resWrapper.contentAsByteArray
                    if (body.size <= IdempotencyKeyStore.MAX_BODY_BYTES) {
                        val text = String(body, Charsets.UTF_8)
                        store.cacheResponse(k, resWrapper.status, text)
                        log.debug("idempotency cache stored key={} status={} bytes={}",
                            k, resWrapper.status, body.size)
                    } else {
                        // 16KB 초과 — cache skip. 다음 retry 도 정상 처리 path 를 탄다.
                        log.warn("idempotency response too large, skip cache key={} bytes={}",
                            k, body.size)
                    }
                }
            } finally {
                resWrapper.copyBodyToResponse()
            }
        }
    }

    /**
     * 미리 buffer 된 bodyBytes 를 controller 가 다시 읽을 수 있도록 노출하는 wrapper.
     *
     * <p>{@code getInputStream()} / {@code getReader()} 둘 다 같은 byte[] 위에서 새 stream 을 만들어
     * 반환 — controller 가 단 한 번만 읽는 (Spring MVC @RequestBody) 일반 흐름에서 안전.</p>
     */
    private class CachedBodyHttpServletRequest(
        request: HttpServletRequest,
        private val body: ByteArray,
    ) : HttpServletRequestWrapper(request) {

        override fun getInputStream(): ServletInputStream {
            val byteStream = ByteArrayInputStream(body)
            return object : ServletInputStream() {
                override fun isFinished() = byteStream.available() == 0
                override fun isReady() = true
                override fun setReadListener(listener: ReadListener?) {
                    // sync read only — async streaming 은 우리 도메인에 없음.
                    throw UnsupportedOperationException("async read not supported")
                }
                override fun read() = byteStream.read()
            }
        }

        override fun getReader(): BufferedReader =
            BufferedReader(InputStreamReader(getInputStream(), Charsets.UTF_8))

        override fun getContentLength(): Int = body.size
        override fun getContentLengthLong(): Long = body.size.toLong()
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
        /** 캐시 hit 시 client 가 replay 임을 알 수 있도록 응답 헤더로 표시 (결제 API 통상 명세). */
        const val REPLAY_HEADER = "Idempotent-Replayed"
    }
}
