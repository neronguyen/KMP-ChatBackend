package io.github.neronguyenvn.nerochat.api.advice

import io.github.neronguyenvn.nerochat.api.validation.InvalidUuidException
import io.github.neronguyenvn.nerochat.contract.error.ApiErrorDto
import io.github.neronguyenvn.nerochat.contract.error.CommonServerError
import io.github.neronguyenvn.nerochat.domain.exception.RateLimitExceededException
import io.github.neronguyenvn.nerochat.domain.exception.UnauthorizedException
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.beans.TypeMismatchException
import org.springframework.core.MethodParameter
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.mock.http.MockHttpInputMessage
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.validation.BeanPropertyBindingResult
import org.springframework.validation.FieldError
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.ErrorResponseException
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.server.ResponseStatusException

class CommonExceptionHandlerTest {

    private val handler = CommonExceptionHandler()

    private fun handleSpringMvcException(ex: Exception): ResponseEntity<ApiErrorDto> {
        return when (ex) {
            is MethodArgumentNotValidException -> handler.onMethodArgumentNotValid(ex)
            is HttpMessageNotReadableException -> handler.onHttpMessageNotReadable(ex)
            is MissingServletRequestParameterException -> handler.onMissingServletRequestParameter(ex)
            is TypeMismatchException -> handler.onTypeMismatch(ex)
            is ErrorResponseException -> handler.onErrorResponse(ex)
            else -> handler.onUnhandledException(ex)
        }
    }

    @Test
    fun `handles UnauthorizedException with 401 status and UNAUTHORIZED error`() {
        val ex = UnauthorizedException("Token has expired")
        val response = handler.onUnauthorized(ex)

        assertEquals(HttpStatus.UNAUTHORIZED, response.statusCode)
        assertNotNull(response.body)
        assertEquals(CommonServerError.Unauthorized.code, response.body?.error)
        assertEquals("Token has expired", response.body?.message)
    }

    @Test
    fun `handles RateLimitExceededException with 429 status and RATE_LIMIT_EXCEEDED error`() {
        val resetsInSeconds = 45L
        val ex = RateLimitExceededException(resetsInSeconds)
        val response = handler.onRateLimitExceeded(ex)

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, response.statusCode)
        assertEquals(resetsInSeconds.toString(), response.headers.getFirst(HttpHeaders.RETRY_AFTER))
        assertNotNull(response.body)
        assertEquals(CommonServerError.RateLimitExceeded.code, response.body?.error)
        assertEquals(ex.message, response.body?.message)
        assertEquals(resetsInSeconds, response.body?.retryAfterSeconds)
    }

    @Test
    fun `handles InvalidUuidException with 400 status and does not leak invalid value`() {
        val ex = InvalidUuidException(parameterName = "roomId", invalidValue = "not-a-valid-uuid")
        val response = handler.onInvalidUuid(ex)

        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
        assertNotNull(response.body)
        assertEquals(CommonServerError.BadRequest.code, response.body?.error)
        assertEquals("Parameter 'roomId' must be a valid UUID", response.body?.message)
        assertFalse(response.body?.message?.contains("not-a-valid-uuid") == true)
    }

    @Test
    fun `handles MethodArgumentNotValidException with 400 status and VALIDATION_FAILED error`() {
        val method = javaClass.getDeclaredMethod("dummyMethod", String::class.java)
        val parameter = MethodParameter(method, 0)
        val bindingResult = BeanPropertyBindingResult("dummyTarget", "target")
        bindingResult.addError(FieldError("target", "email", "Email format is invalid"))
        bindingResult.addError(FieldError("target", "password", "Password cannot be blank"))

        val ex = MethodArgumentNotValidException(parameter, bindingResult)
        val response = handleSpringMvcException(ex)

        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
        val body = response.body
        assertNotNull(body)
        assertEquals(CommonServerError.ValidationFailed.code, body?.error)
        assertEquals("Email format is invalid", body?.message)
        assertEquals("Email format is invalid", body?.fieldErrors?.get("email"))
        assertEquals("Password cannot be blank", body?.fieldErrors?.get("password"))
    }

    @Suppress("unused")
    private fun dummyMethod(payload: String) {
    }

    @Test
    fun `handles HttpMessageNotReadableException with 400 status and BAD_REQUEST error`() {
        val ex = HttpMessageNotReadableException(
            "JSON parse error",
            MockHttpInputMessage(ByteArray(0))
        )
        val response = handleSpringMvcException(ex)

        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
        val body = response.body
        assertNotNull(body)
        assertEquals(CommonServerError.BadRequest.code, body?.error)
        assertEquals("Malformed request body", body?.message)
        assertTrue(body?.fieldErrors?.isEmpty() == true)
    }

    @Test
    fun `handles MissingServletRequestParameterException with 400 status and parameter name`() {
        val ex = MissingServletRequestParameterException("query", "String")
        val response = handleSpringMvcException(ex)

        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
        val body = response.body
        assertNotNull(body)
        assertEquals(CommonServerError.BadRequest.code, body?.error)
        assertEquals("Missing required parameter: 'query'", body?.message)
    }

    @Test
    fun `handles MethodArgumentTypeMismatchException with 400 status without echoing invalid value`() {
        val method = javaClass.getDeclaredMethod("dummyMethod", String::class.java)
        val parameter = MethodParameter(method, 0)
        val ex = MethodArgumentTypeMismatchException("raw-value", String::class.java, "filterId", parameter, null)
        val response = handleSpringMvcException(ex)

        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
        val body = response.body
        assertNotNull(body)
        assertEquals(CommonServerError.BadRequest.code, body?.error)
        assertEquals("Invalid parameter: 'filterId'", body?.message)
        assertFalse(body?.message?.contains("raw-value") == true)
    }

    @Test
    fun `handles ErrorResponse via handleExceptionInternal preserving 4xx status code and detail`() {
        val ex = ResponseStatusException(HttpStatus.NOT_FOUND, "Resource not found")
        val response = handleSpringMvcException(ex)

        assertEquals(HttpStatus.NOT_FOUND, response.statusCode)
        val body = response.body
        assertNotNull(body)
        assertEquals(CommonServerError.BadRequest.code, body?.error)
        assertEquals("Resource not found", body?.message)
    }

    @Test
    fun `handles ErrorResponse with 5xx status via handleExceptionInternal returning generic 500 error response`() {
        val ex = ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Database connection failed")
        val response = handleSpringMvcException(ex)

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.statusCode)
        val body = response.body
        assertNotNull(body)
        assertEquals(CommonServerError.InternalServerError.code, body?.error)
        assertEquals("An unexpected error occurred", body?.message)
    }

    @Test
    fun `handles unexpected generic exception with 500 status`() {
        val ex = RuntimeException("Database connection timeout")
        val response = handler.onUnhandledException(ex)

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.statusCode)
        assertNotNull(response.body)
        assertEquals(CommonServerError.InternalServerError.code, response.body?.error)
        assertEquals("An unexpected error occurred", response.body?.message)
    }

    @Test
    fun `treats unhandled IllegalArgumentException as 500 internal server error`() {
        val ex = IllegalArgumentException("Unexpected internal illegal argument")
        val response = handler.onUnhandledException(ex)

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.statusCode)
        assertNotNull(response.body)
        assertEquals(CommonServerError.InternalServerError.code, response.body?.error)
        assertEquals("An unexpected error occurred", response.body?.message)
    }
}
