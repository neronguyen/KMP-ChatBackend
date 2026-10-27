package io.github.neronguyenvn.nerochat.api.security

import io.github.neronguyenvn.nerochat.api.config.TrustedProxyConfig
import io.github.neronguyenvn.nerochat.contract.error.ApiErrorResponse
import io.github.neronguyenvn.nerochat.contract.error.CommonServerError
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import jakarta.servlet.http.HttpServletResponse
import kotlinx.serialization.json.Json
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.MediaType
import org.springframework.security.web.util.matcher.IpAddressMatcher
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class TrustedProxyFilter(
    private val config: TrustedProxyConfig,
    private val json: Json = Json { ignoreUnknownKeys = true }
) : OncePerRequestFilter() {

    private val matchers: List<IpAddressMatcher> = config.trustedIps.map { ip ->
        val cidr = when {
            ip.contains("/") -> ip
            ip.contains(":") -> "$ip/128"
            else -> "$ip/32"
        }

        IpAddressMatcher(cidr)
    }

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        val socketIp = request.remoteAddr
        val isTrusted = matchers.any { it.matches(socketIp) }

        if (!isTrusted && config.requireProxy) {
            response.status = HttpServletResponse.SC_FORBIDDEN
            response.contentType = MediaType.APPLICATION_JSON_VALUE
            val errorResponse: ApiErrorResponse<CommonServerError> = ApiErrorResponse(
                error = CommonServerError.Unauthorized,
                message = "Direct connections not allowed"
            )
            response.writer.write(json.encodeToString(errorResponse))
            return
        }

        val realClientIp = if (isTrusted) {
            request.getHeader(REAL_IP_HEADER)?.trim()?.takeIf { it.isNotBlank() } ?: socketIp
        } else {
            socketIp
        }

        val wrappedRequest = object : HttpServletRequestWrapper(request) {
            override fun getRemoteAddr(): String = realClientIp
        }

        filterChain.doFilter(wrappedRequest, response)
    }

    companion object {
        private const val REAL_IP_HEADER = "X-Real-IP"
    }
}
