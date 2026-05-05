package com.example.billing.adapter.web.auth

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain

/**
 * 로컬 dev / 테스트용 — 모든 요청 허용. wallet.security.jwt.enabled 가 미설정/false 일 때 활성.
 */
@Configuration
@ConditionalOnProperty(name = ["wallet.security.jwt.enabled"], havingValue = "false", matchIfMissing = true)
class PermissiveSecurityConfig {

    @Bean
    fun permissiveFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests { it.anyRequest().permitAll() }
            .httpBasic { it.disable() }
            .formLogin { it.disable() }
        return http.build()
    }
}
