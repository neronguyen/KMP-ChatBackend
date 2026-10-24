package io.github.neronguyenvn.nerochat.user.api.config

import io.github.neronguyenvn.nerochat.user.service.ratelimiting.IpRateLimitingService
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.method.HandlerMethod
import org.springframework.web.servlet.HandlerInterceptor
import java.time.Duration

@Component
class IpRateLimitingInterceptor(
    private val ipRateLimit: IpRateLimitingService,
    @param:Value($$"${rate-limit.ip.apply-limit}")
    private val applyLimit: Boolean
) : HandlerInterceptor {

    override fun preHandle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any
    ): Boolean {
        if (handler is HandlerMethod && applyLimit) {
            val annotation = handler.getMethodAnnotation(
                IpRateLimiting::class.java
            ) ?: return true

            val clientIp = request.remoteAddr?.takeIf { it.isNotBlank() } ?: "unknown"

            val methodKey = "${handler.beanType.simpleName}:${handler.method.name}"
            val compositeKey = "$clientIp:$methodKey"

            ipRateLimit(
                key = compositeKey,
                maxRequestsPerIp = annotation.maxRequestsPerIp,
                resetIn = Duration.of(
                    annotation.resetIn,
                    annotation.timeUnit.toChronoUnit()
                ),
                action = { true }
            )
            return true
        }

        return true
    }
}
