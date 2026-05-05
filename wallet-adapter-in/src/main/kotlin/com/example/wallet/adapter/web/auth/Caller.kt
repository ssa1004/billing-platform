package com.example.wallet.adapter.web.auth

import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt

/**
 * 호출자 컨텍스트. JWT 가 있으면 sub → owner, 없으면 anonymous (PermissiveSecurityConfig).
 *
 * Controller 가 [Caller.from(jwt)] 로 한 줄에 추출 → owner / isAdmin 사용.
 */
data class Caller(val owner: String, val isAdmin: Boolean) {
    companion object {
        const val ANONYMOUS = "anonymous"
        const val ADMIN_ROLE = "ROLE_admin"

        fun from(jwt: Jwt?): Caller {
            if (jwt != null) {
                return Caller(jwt.subject ?: ANONYMOUS, hasAdmin())
            }
            val auth = SecurityContextHolder.getContext().authentication
            if (auth == null || !auth.isAuthenticated) return Caller(ANONYMOUS, false)
            val name = auth.name?.takeIf { it.isNotBlank() } ?: ANONYMOUS
            return Caller(name, hasAdmin())
        }

        private fun hasAdmin(): Boolean {
            val auth = SecurityContextHolder.getContext().authentication ?: return false
            return auth.authorities.any { it.authority == ADMIN_ROLE }
        }
    }
}
