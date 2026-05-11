package com.example.billing.adapter.web.filter

import io.micrometer.core.instrument.MeterRegistry
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/**
 * 운영 환경에서 v1 / v2 endpoint 의 사용량을 추적 — `api.version.usage` 카운터 (ADR-0031).
 *
 * 라벨:
 *  - `version`  — "v1" / "v2" / "other"
 *  - `path`     — `/api/v1/invoices` 같은 path prefix (resource 만, id 는 제외)
 *
 * v1 deprecation cutover 시점 결정에 사용 — v1 카운터가 충분히 떨어지면 운영 공지 + 코드 제거.
 *
 * Path 추출은 prefix only (resource level) — id 마다 cardinality 가 폭발하지 않게. id 는
 * 라벨에서 빠짐.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 30)
class ApiVersionMetricsFilter(
    private val meterRegistry: MeterRegistry,
) : OncePerRequestFilter() {

    override fun doFilterInternal(req: HttpServletRequest, res: HttpServletResponse, chain: FilterChain) {
        val uri = req.requestURI
        val (version, resource) = parseVersionAndResource(uri)
        if (version != null) {
            meterRegistry.counter(
                "api.version.usage",
                "version", version,
                "resource", resource,
            ).increment()
        }
        chain.doFilter(req, res)
    }

    /**
     * `/api/v1/invoices/abc-123` → `v1`, `/api/v1/invoices`
     * `/api/v2/invoices?customerId=...` → `v2`, `/api/v2/invoices`
     * `/health` → null, ""
     */
    internal fun parseVersionAndResource(uri: String): Pair<String?, String> {
        val parts = uri.trim('/').split('/')
        if (parts.size < 2 || parts[0] != "api") return null to ""
        val v = parts[1]
        if (v != "v1" && v != "v2") return null to ""
        // resource: /api/v?/<resource> — 최대 한 단계 segment 만 라벨에 포함
        val resource = if (parts.size >= 3) "/api/$v/${parts[2]}" else "/api/$v"
        return v to resource
    }
}
