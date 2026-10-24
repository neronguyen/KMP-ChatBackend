package io.github.neronguyenvn.nerochat.user.api.advice

import io.github.neronguyenvn.nerochat.contract.auth.error.AuthServerError
import io.github.neronguyenvn.nerochat.contract.error.ApiErrorResponse
import io.github.neronguyenvn.nerochat.domain.exception.InvalidTokenException
import io.github.neronguyenvn.nerochat.user.domain.exception.EmailNotVerifiedException
import io.github.neronguyenvn.nerochat.user.domain.exception.InvalidCredentialsException
import io.github.neronguyenvn.nerochat.user.domain.exception.RateLimitExceededException
import io.github.neronguyenvn.nerochat.user.domain.exception.SamePasswordException
import io.github.neronguyenvn.nerochat.user.domain.exception.UserAlreadyExistsException
import io.github.neronguyenvn.nerochat.user.domain.exception.UserNotFoundException
import io.github.neronguyenvn.nerochat.user.domain.exception.WrongPasswordException
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class AuthExceptionHandler {

    @ExceptionHandler(InvalidTokenException::class)
    fun onInvalidToken(e: InvalidTokenException): ResponseEntity<ApiErrorResponse> =
        ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(
            ApiErrorResponse(
                code = AuthServerError.InvalidToken.code,
                message = e.message,
            )
        )

    @ExceptionHandler(UserNotFoundException::class)
    fun onUserNotFound(e: UserNotFoundException): ResponseEntity<ApiErrorResponse> =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(
            ApiErrorResponse(
                code = AuthServerError.UserNotFound.code,
                message = e.message,
            )
        )

    @ExceptionHandler(WrongPasswordException::class)
    fun onPasswordMismatch(e: WrongPasswordException): ResponseEntity<ApiErrorResponse> =
        ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(
            ApiErrorResponse(
                code = AuthServerError.WrongPassword.code,
                message = e.message,
            )
        )

    @ExceptionHandler(EmailNotVerifiedException::class)
    fun onEmailNotVerified(e: EmailNotVerifiedException): ResponseEntity<ApiErrorResponse> =
        ResponseEntity.status(HttpStatus.FORBIDDEN).body(
            ApiErrorResponse(
                code = AuthServerError.EmailNotVerified.code,
                message = e.message,
            )
        )

    @ExceptionHandler(InvalidCredentialsException::class)
    fun onInvalidCredentials(e: InvalidCredentialsException): ResponseEntity<ApiErrorResponse> =
        ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(
            ApiErrorResponse(
                code = AuthServerError.InvalidCredentials.code,
                message = e.message,
            )
        )

    @ExceptionHandler(SamePasswordException::class)
    fun onSamePassword(e: SamePasswordException): ResponseEntity<ApiErrorResponse> =
        ResponseEntity.status(HttpStatus.CONFLICT).body(
            ApiErrorResponse(
                code = AuthServerError.SamePassword.code,
                message = e.message,
            )
        )

    @ExceptionHandler(UserAlreadyExistsException::class)
    fun onUserAlreadyExists(e: UserAlreadyExistsException): ResponseEntity<ApiErrorResponse> =
        ResponseEntity.status(HttpStatus.CONFLICT).body(
            ApiErrorResponse(
                code = AuthServerError.UserAlreadyExists.code,
                message = e.message,
            )
        )

    @ExceptionHandler(RateLimitExceededException::class)
    fun onRateLimitExceeded(e: RateLimitExceededException): ResponseEntity<ApiErrorResponse> =
        ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
            .header(HttpHeaders.RETRY_AFTER, e.resetsInSeconds.toString())
            .body(
                ApiErrorResponse(
                    code = AuthServerError.RateLimitExceeded.code,
                    message = e.message,
                    retryAfterSeconds = e.resetsInSeconds,
                )
            )
}