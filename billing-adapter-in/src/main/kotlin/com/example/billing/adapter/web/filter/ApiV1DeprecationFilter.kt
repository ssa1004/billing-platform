package com.example.billing.adapter.web.filter

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/**
 * v1 endpoint 응답에 `Deprecation` / `Sunset` HTTP 헤더 자동 부착 (ADR-0031).
 *
 * - **`Deprecation`** (RFC 9745): "이 endpoint 는 deprecated 되었음" 시그널. 표준은 boolean
 *   문자열 또는 deprecated 시점의 HTTP-date. 우리는 boolean true 사용 — 시점은 공지로 별도.
 * - **`Sunset`** (RFC 8594): "이 endpoint 가 제거될 시점" 의 HTTP-date. client 는 이 시점을
 *   기준으로 v2 마이그레이션 일정 잡음.
 * - **`Link`** with `rel="successor-version"`: 다음 버전 endpoint 가리킴.
 *
 * `billing.api.v1.sunset-at` 가 설정 안 되어 있으면 헤더는 부착되지 않음 — *deprecation 공식
 * 선언 전엔 v1 도 1급 시민*. 실제 sunset 시점이 정해지면 그때 yml 에 값 채움.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 40)
class ApiV1DeprecationFilter(
    @Value("\${billing.api.v1.sunset-at:}") private val sunsetAt: String,
) : OncePerRequestFilter() {

    override fun doFilterInternal(req: HttpServletRequest, res: HttpServletResponse, chain: FilterChain) {
        val isV1 = req.requestURI.startsWith("/api/v1/")
        if (isV1 && sunsetAt.isNotBlank()) {
            res.setHeader("Deprecation", "true")
            res.setHeader("Sunset", sunsetAt)
            res.addHeader("Link", "</api/v2${stripV1(req.requestURI)}>; rel=\"successor-version\"")
        }
        chain.doFilter(req, res)
    }

    private fun stripV1(uri: String): String = uri.removePrefix("/api/v1")
}
