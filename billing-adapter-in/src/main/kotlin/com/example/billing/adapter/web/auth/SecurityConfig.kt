package com.example.billing.adapter.web.auth

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter
import org.springframework.security.web.SecurityFilterChain

/**
 * 운영 보안: OAuth2 Resource Server (JWT). billing.security.jwt.enabled=true 일 때 활성.
 */
@Configuration
@EnableMethodSecurity
@ConditionalOnProperty(name = ["billing.security.jwt.enabled"], havingValue = "true")
class SecurityConfig {

    @Bean
    fun filterChain(http: HttpSecurity): SecurityFilterChain = http
        .csrf { it.disable() }
        .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
        .authorizeHttpRequests {
            it.requestMatchers(
                "/actuator/health/**", "/actuator/info",
                "/v3/api-docs/**", "/swagger", "/swagger-ui/**", "/swagger-ui.html",
            ).permitAll()
            it.requestMatchers("/actuator/prometheus").permitAll()
            it.requestMatchers("/actuator/modulith/**").permitAll()
            it.requestMatchers("/api/**").authenticated()
            it.anyRequest().denyAll()
        }
        .oauth2ResourceServer { oauth2 ->
            oauth2.jwt { jwt -> jwt.jwtAuthenticationConverter(jwtConverter()) }
        }
        .build()

    private fun jwtConverter(): JwtAuthenticationConverter {
        val scopes = JwtGrantedAuthoritiesConverter().apply {
            setAuthorityPrefix("SCOPE_")
            setAuthoritiesClaimName("scope")
        }
        return JwtAuthenticationConverter().apply {
            setPrincipalClaimName("sub")
            setJwtGrantedAuthoritiesConverter { jwt ->
                buildList {
                    scopes.convert(jwt)?.let(::addAll)
                    val realmRoles = (jwt.getClaim<Map<String, Any>>("realm_access")
                        ?.get("roles") as? List<*>)
                        ?.filterIsInstance<String>()
                        .orEmpty()
                    realmRoles.forEach { role ->
                        add(org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_$role"))
                    }
                }
            }
        }
    }
}
