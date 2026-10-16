package io.github.neronguyenvn.nerochat.api.advice

import io.github.neronguyenvn.nerochat.domain.exception.ForbiddenException
import io.github.neronguyenvn.nerochat.domain.exception.UnauthorizedException
import io.github.neronguyenvn.nerochat.domain.exception.ConflictException
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class CommonExceptionHandler {

    @ExceptionHandler(UnauthorizedException::class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    fun onUnauthorized(e: UnauthorizedException) = mapOf(
        "code" to "UNAUTHORIZED",
        "message" to e.message
    )

    @ExceptionHandler(ForbiddenException::class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    fun onForbidden(e: ForbiddenException) = mapOf(
        "code" to "FORBIDDEN",
        "message" to e.message
    )

    @ExceptionHandler(ConflictException::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    fun onConflict(e: ConflictException) = mapOf(
        "code" to "CONFLICT",
        "message" to e.message
    )
}
