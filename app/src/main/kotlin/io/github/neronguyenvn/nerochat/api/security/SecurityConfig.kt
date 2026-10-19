package io.github.neronguyenvn.nerochat.api.security

import io.github.neronguyenvn.nerochat.contract.error.ApiErrorResponse
import io.github.neronguyenvn.nerochat.contract.error.ServerError
import jakarta.servlet.http.HttpServletResponse
import kotlinx.serialization.json.Json
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.MediaType
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.access.AccessDeniedHandler
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter

@Configuration
class SecurityConfig(
    private val json: Json = Json { ignoreUnknownKeys = true }
) {

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
                configurer.authenticationEntryPoint(apiAuthenticationEntryPoint())
                configurer.accessDeniedHandler(apiAccessDeniedHandler())
            }
            .build()
    }

    private fun apiAuthenticationEntryPoint(): AuthenticationEntryPoint =
        AuthenticationEntryPoint { _, response, _ ->
            response.status = HttpServletResponse.SC_UNAUTHORIZED
            response.contentType = MediaType.APPLICATION_JSON_VALUE
            val errorResponse = ApiErrorResponse(
                code = ServerError.UNAUTHORIZED,
                message = "Authentication required",
            )
            response.writer.write(json.encodeToString(errorResponse))
        }

    private fun apiAccessDeniedHandler(): AccessDeniedHandler =
        AccessDeniedHandler { _, response, _ ->
            response.status = HttpServletResponse.SC_FORBIDDEN
            response.contentType = MediaType.APPLICATION_JSON_VALUE
            val errorResponse = ApiErrorResponse(
                code = ServerError.FORBIDDEN,
                message = "Access denied",
            )
            response.writer.write(json.encodeToString(errorResponse))
        }
}
