package io.github.neronguyenvn.nerochat.user.api.advice

import io.github.neronguyenvn.nerochat.contract.auth.error.AuthServerError
import io.github.neronguyenvn.nerochat.contract.error.ApiErrorDto
import io.github.neronguyenvn.nerochat.domain.exception.InvalidTokenException
import io.github.neronguyenvn.nerochat.user.domain.exception.EmailNotVerifiedException
import io.github.neronguyenvn.nerochat.user.domain.exception.InvalidCredentialsException
import io.github.neronguyenvn.nerochat.user.domain.exception.SamePasswordException
import io.github.neronguyenvn.nerochat.user.domain.exception.UserAlreadyExistsException
import io.github.neronguyenvn.nerochat.user.domain.exception.UserNotFoundException
import io.github.neronguyenvn.nerochat.user.domain.exception.WrongPasswordException
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class AuthExceptionHandler {

    @ExceptionHandler(InvalidTokenException::class)
    fun onInvalidToken(e: InvalidTokenException): ResponseEntity<ApiErrorDto> =
        ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(
            ApiErrorDto(
                error = AuthServerError.InvalidToken,
                message = e.message,
            )
        )

    @ExceptionHandler(UserNotFoundException::class)
    fun onUserNotFound(e: UserNotFoundException): ResponseEntity<ApiErrorDto> =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(
            ApiErrorDto(
                error = AuthServerError.UserNotFound,
                message = e.message,
            )
        )

    @ExceptionHandler(WrongPasswordException::class)
    fun onPasswordMismatch(e: WrongPasswordException): ResponseEntity<ApiErrorDto> =
        ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(
            ApiErrorDto(
                error = AuthServerError.WrongPassword,
                message = e.message,
            )
        )

    @ExceptionHandler(EmailNotVerifiedException::class)
    fun onEmailNotVerified(e: EmailNotVerifiedException): ResponseEntity<ApiErrorDto> =
        ResponseEntity.status(HttpStatus.FORBIDDEN).body(
            ApiErrorDto(
                error = AuthServerError.EmailNotVerified,
                message = e.message,
            )
        )

    @ExceptionHandler(InvalidCredentialsException::class)
    fun onInvalidCredentials(e: InvalidCredentialsException): ResponseEntity<ApiErrorDto> =
        ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(
            ApiErrorDto(
                error = AuthServerError.InvalidCredentials,
                message = e.message,
            )
        )

    @ExceptionHandler(SamePasswordException::class)
    fun onSamePassword(e: SamePasswordException): ResponseEntity<ApiErrorDto> =
        ResponseEntity.status(HttpStatus.CONFLICT).body(
            ApiErrorDto(
                error = AuthServerError.SamePassword,
                message = e.message,
            )
        )

    @ExceptionHandler(UserAlreadyExistsException::class)
    fun onUserAlreadyExists(e: UserAlreadyExistsException): ResponseEntity<ApiErrorDto> =
        ResponseEntity.status(HttpStatus.CONFLICT).body(
            ApiErrorDto(
                error = AuthServerError.UserAlreadyExists,
                message = e.message,
            )
        )
}