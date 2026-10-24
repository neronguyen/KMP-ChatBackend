package io.github.neronguyenvn.nerochat.api.security

import io.github.neronguyenvn.nerochat.domain.exception.InvalidTokenException
import io.github.neronguyenvn.nerochat.service.JwtService
import io.github.neronguyenvn.nerochat.user.domain.exception.UserNotFoundException
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

@Component
class JwtAuthenticationFilter(private val jwtService: JwtService) : OncePerRequestFilter() {

    /**
     * Sets the security principal from a valid access token, then continues the filter chain.
     * Missing or rejected tokens leave the current authentication unchanged. [UserNotFoundException]
     * is ignored; other errors from extracting the principal or the remaining filters propagate.
     */
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        val accessToken = resolveToken(request)
        if (accessToken != null && jwtService.validateAccessToken(accessToken)) {
            try {
                val userId = jwtService.getUserIdFromToken(accessToken)
                val auth = UsernamePasswordAuthenticationToken(
                    userId,
                    null,
                    emptyList()
                )
                SecurityContextHolder.getContext().authentication = auth
            } catch (_: InvalidTokenException) {
                // leave context anonymous; downstream authorization will return 401
            } catch (_: UserNotFoundException) {
                // leave context anonymous; downstream authorization will return 401
            }
        }

        filterChain.doFilter(request, response)
    }

    override fun shouldNotFilter(request: HttpServletRequest): Boolean {
        return request.servletPath.startsWith("/ws/")
    }

    /** Returns the Authorization header after an exact `Bearer ` prefix, or null if absent or unmatched. */
    private fun resolveToken(request: HttpServletRequest): String? {
        val bearerToken = request.getHeader(AUTHORIZATION_HEADER) ?: return null
        if (!bearerToken.startsWith(AUTHORIZATION_PREFIX)) return null
        return bearerToken.drop(AUTHORIZATION_PREFIX.length)
    }

    companion object {
        private const val AUTHORIZATION_HEADER = "Authorization"
        private const val AUTHORIZATION_PREFIX = "Bearer "
    }
}
