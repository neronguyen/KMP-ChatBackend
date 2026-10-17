package io.github.neronguyenvn.nerochat.api.security

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.HttpStatusEntryPoint
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter

@Configuration
class SecurityConfig {

    /**
     * Configures stateless JWT authentication with a 401 entry point and disables CSRF, form login,
     * and HTTP Basic. Auth endpoints are public except change-password; other API routes require
     * an authenticated principal. Requests outside the API are permitted.
     */
    @Bean
    fun securityFilterChain(
        http: HttpSecurity,
        jwtAuthenticationFilter: JwtAuthenticationFilter
    ): SecurityFilterChain {
        return http
            .csrf { it.disable() }
            .formLogin { it.disable() }
            .httpBasic { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests { auth ->
                auth
                    // Error handling
                    .requestMatchers("/error").permitAll()

                    // WebSocket handshake
                    .requestMatchers("/ws/**").permitAll()

                    // Auth routes
                    .requestMatchers("/api/auth/change-password").authenticated()
                    .requestMatchers("/api/auth/**").permitAll()

                    // API routes
                    .requestMatchers("/api/**").authenticated()

                    // All other routes
                    .anyRequest().authenticated()
            }
            .addFilterBefore(
                jwtAuthenticationFilter,
                UsernamePasswordAuthenticationFilter::class.java
            )
            .exceptionHandling { configurer ->
                val unauthorized = HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)
                configurer.authenticationEntryPoint(unauthorized)
            }
            .build()
    }
}
