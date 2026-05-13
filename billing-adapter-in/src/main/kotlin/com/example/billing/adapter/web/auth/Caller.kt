package com.example.billing.adapter.web.auth

import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt

/**
 * 호출자 컨텍스트. JWT 가 있으면 sub → owner, 없으면 anonymous (PermissiveSecurityConfig).
 *
 * Controller 가 [Caller.from(jwt)] 로 한 줄에 추출 → owner / isAdmin / [requireOwnerOrAdmin]
 * 으로 BOLA (OWASP API1) 방어. owner 는 보통 customer 식별자 (B2B SaaS — sub claim 이 tenant
 * 식별).
 *
 * **admin 판정**: Spring Security 의 `hasRole('ADMIN')` 은 권한 prefix `ROLE_` 뒤의 부분만
 * 비교하기 때문에 JWT realm role 표기 (`admin` vs `ADMIN`) 와 Kotlin 측 상수가 불일치하면
 * 한쪽이 통과해도 다른 쪽이 거절하는 사고가 납니다. 둘 다 소문자 비교로 통일.
 */
data class Caller(val owner: String, val isAdmin: Boolean) {
    /**
     * 요청에서 받은 resource owner 가 caller 자신인지 검사. admin 은 항상 통과 (운영자가
     * customer 데이터를 조회/보정하는 흐름).
     *
     * @throws AccessDeniedException 403 으로 매핑됨 ([com.example.billing.adapter.web.exception.GlobalExceptionHandler]).
     */
    fun requireOwnerOrAdmin(requestedOwner: String) {
        if (isAdmin) return
        if (owner == ANONYMOUS) {
            // PermissiveSecurityConfig (jwt.enabled=false) — 로컬 dev / 테스트에서만. 인증 자체가
            // 없으니 owner 검사는 의미가 없고, 그 환경에선 통과시킨다. JWT 활성 환경 (prod) 에서
            // 만 진짜 비교.
            return
        }
        if (owner != requestedOwner) {
            throw AccessDeniedException(
                "caller '$owner' is not the owner of resource (requested '$requestedOwner')",
            )
        }
    }

    companion object {
        const val ANONYMOUS = "anonymous"

        /**
         * 권한 prefix. Spring Security 의 `hasRole('admin')` 은 내부에서 `ROLE_admin` 으로
         * 변환해 비교 — 둘 모두 같은 prefix 를 봐야 한다.
         */
        const val ADMIN_ROLE = "ROLE_admin"

        fun from(jwt: Jwt?): Caller {
            jwt?.let { return Caller(it.subject ?: ANONYMOUS, hasAdmin()) }

            val auth = SecurityContextHolder.getContext().authentication
            if (auth?.isAuthenticated != true) return Caller(ANONYMOUS, false)
            val name = auth.name?.takeIf(String::isNotBlank) ?: ANONYMOUS
            return Caller(name, hasAdmin())
        }

        private fun hasAdmin(): Boolean {
            val auth = SecurityContextHolder.getContext().authentication ?: return false
            // case-insensitive — JWT realm role 이 "admin" / "ADMIN" 둘 다 통과되도록.
            return auth.authorities.any { it.authority.equals(ADMIN_ROLE, ignoreCase = true) }
        }
    }
}
