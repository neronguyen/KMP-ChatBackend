package io.github.neronguyenvn.nerochat.user.api.advice

import io.github.neronguyenvn.nerochat.contract.auth.error.AuthServerError
import io.github.neronguyenvn.nerochat.domain.exception.InvalidTokenException
import io.github.neronguyenvn.nerochat.user.domain.exception.EmailNotVerifiedException
import io.github.neronguyenvn.nerochat.user.domain.exception.InvalidCredentialsException
import io.github.neronguyenvn.nerochat.user.domain.exception.RateLimitExceededException
import io.github.neronguyenvn.nerochat.user.domain.exception.SamePasswordException
import io.github.neronguyenvn.nerochat.user.domain.exception.UserAlreadyExistsException
import io.github.neronguyenvn.nerochat.user.domain.exception.UserNotFoundException
import io.github.neronguyenvn.nerochat.user.domain.exception.WrongPasswordException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus

class AuthExceptionHandlerTest {

    private val handler = AuthExceptionHandler()

    @Test
    fun `onInvalidToken returns 401 and INVALID_TOKEN code`() {
        val ex = InvalidTokenException("Token has expired")
        val response = handler.onInvalidToken(ex)

        assertEquals(HttpStatus.UNAUTHORIZED, response.statusCode)
        assertNotNull(response.body)
        assertEquals(AuthServerError.InvalidToken.code, response.body?.code)
        assertEquals("Token has expired", response.body?.message)
    }

    @Test
    fun `onUserNotFound returns 404 and USER_NOT_FOUND code`() {
        val ex = UserNotFoundException.byEmail()
        val response = handler.onUserNotFound(ex)

        assertEquals(HttpStatus.NOT_FOUND, response.statusCode)
        assertNotNull(response.body)
        assertEquals(AuthServerError.UserNotFound.code, response.body?.code)
        assertEquals(ex.message, response.body?.message)
    }

    @Test
    fun `onPasswordMismatch returns 401 and WRONG_PASSWORD code`() {
        val ex = WrongPasswordException()
        val response = handler.onPasswordMismatch(ex)

        assertEquals(HttpStatus.UNAUTHORIZED, response.statusCode)
        assertNotNull(response.body)
        assertEquals(AuthServerError.WrongPassword.code, response.body?.code)
        assertEquals(ex.message, response.body?.message)
    }

    @Test
    fun `onEmailNotVerified returns 403 and EMAIL_NOT_VERIFIED code`() {
        val ex = EmailNotVerifiedException()
        val response = handler.onEmailNotVerified(ex)

        assertEquals(HttpStatus.FORBIDDEN, response.statusCode)
        assertNotNull(response.body)
        assertEquals(AuthServerError.EmailNotVerified.code, response.body?.code)
        assertEquals(ex.message, response.body?.message)
    }

    @Test
    fun `onInvalidCredentials returns 401 and INVALID_CREDENTIALS code`() {
        val ex = InvalidCredentialsException()
        val response = handler.onInvalidCredentials(ex)

        assertEquals(HttpStatus.UNAUTHORIZED, response.statusCode)
        assertNotNull(response.body)
        assertEquals(AuthServerError.InvalidCredentials.code, response.body?.code)
        assertEquals(ex.message, response.body?.message)
    }

    @Test
    fun `onSamePassword returns 409 and SAME_PASSWORD code`() {
        val ex = SamePasswordException()
        val response = handler.onSamePassword(ex)

        assertEquals(HttpStatus.CONFLICT, response.statusCode)
        assertNotNull(response.body)
        assertEquals(AuthServerError.SamePassword.code, response.body?.code)
        assertEquals(ex.message, response.body?.message)
    }

    @Test
    fun `onUserAlreadyExists returns 409 and USER_ALREADY_EXISTS code`() {
        val ex = UserAlreadyExistsException()
        val response = handler.onUserAlreadyExists(ex)

        assertEquals(HttpStatus.CONFLICT, response.statusCode)
        assertNotNull(response.body)
        assertEquals(AuthServerError.UserAlreadyExists.code, response.body?.code)
        assertEquals(ex.message, response.body?.message)
    }

    @Test
    fun `onRateLimitExceeded returns 429, RATE_LIMIT_EXCEEDED code and retryAfterSeconds header and body`() {
        val resetsInSeconds = 45L
        val ex = RateLimitExceededException(resetsInSeconds)
        val response = handler.onRateLimitExceeded(ex)

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, response.statusCode)
        assertNotNull(response.body)
        assertEquals(AuthServerError.RateLimitExceeded.code, response.body?.code)
        assertEquals(resetsInSeconds, response.body?.retryAfterSeconds)
        assertEquals("45", response.headers.getFirst(HttpHeaders.RETRY_AFTER))
    }
}
