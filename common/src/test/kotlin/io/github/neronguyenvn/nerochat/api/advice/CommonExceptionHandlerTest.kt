package io.github.neronguyenvn.nerochat.api.advice

import io.github.neronguyenvn.nerochat.api.validation.InvalidUuidException
import io.github.neronguyenvn.nerochat.contract.error.ServerError
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.core.MethodParameter
import org.springframework.http.HttpStatus
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.mock.http.MockHttpInputMessage
import org.springframework.security.access.AccessDeniedException
import org.springframework.validation.BeanPropertyBindingResult
import org.springframework.validation.FieldError
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.server.ResponseStatusException

class CommonExceptionHandlerTest {

    private val handler = CommonExceptionHandler()

    @Test
    fun `rethrows AccessDeniedException`() {
        val ex = AccessDeniedException("Access denied by security")
        assertThrows<AccessDeniedException> {
            handler.onAccessDenied(ex)
        }
        assertThrows<AccessDeniedException> {
            handler.onUnhandledException(ex)
        }
    }

    @Test
    fun `handles ErrorResponse preserving 4xx status code and message`() {
        val ex = ResponseStatusException(HttpStatus.NOT_FOUND, "Resource not found")
        val response = handler.onErrorResponse(ex)

        assertEquals(HttpStatus.NOT_FOUND, response.statusCode)
        assertEquals(ServerError.BAD_REQUEST, response.body?.code)
        assertEquals("Resource not found", response.body?.message)
    }

    @Test
    fun `handles ErrorResponse via onUnhandledException fallback`() {
        val ex = ResponseStatusException(HttpStatus.METHOD_NOT_ALLOWED, "Method not allowed")
        val response = handler.onUnhandledException(ex)

        assertEquals(HttpStatus.METHOD_NOT_ALLOWED, response.statusCode)
        assertEquals(ServerError.BAD_REQUEST, response.body?.code)
        assertEquals("Method not allowed", response.body?.message)
    }

    @Test
    fun `handles unexpected generic exception with 500 status`() {
        val ex = RuntimeException("Database connection timeout")
        val response = handler.onUnhandledException(ex)

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.statusCode)
        assertEquals(ServerError.INTERNAL_SERVER_ERROR, response.body?.code)
        assertEquals("An unexpected error occurred", response.body?.message)
    }

    @Suppress("unused")
    private fun dummyMethod(payload: String) {
    }

    @Test
    fun `handles HttpMessageNotReadableException with 400 status and BAD_REQUEST code`() {
        val ex = HttpMessageNotReadableException(
            "JSON parse error",
            MockHttpInputMessage(ByteArray(0))
        )
        val response = handler.onMessageNotReadable(ex)

        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
        assertNotNull(response.body)
        assertEquals(ServerError.BAD_REQUEST, response.body?.code)
        assertEquals("Malformed request body", response.body?.message)
        assertTrue(response.body?.errors?.isEmpty() == true)
        assertTrue(response.body?.fieldErrors?.isEmpty() == true)
    }

    @Test
    fun `handles MethodArgumentNotValidException with 400 status and VALIDATION_FAILED code`() {
        val method = javaClass.getDeclaredMethod("dummyMethod", String::class.java)
        val parameter = MethodParameter(method, 0)
        val bindingResult = BeanPropertyBindingResult("dummyTarget", "target")
        bindingResult.addError(FieldError("target", "email", "Email format is invalid"))
        bindingResult.addError(FieldError("target", "password", "Password cannot be blank"))

        val ex = MethodArgumentNotValidException(parameter, bindingResult)
        val response = handler.onMethodArgumentNotValid(ex)

        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
        assertNotNull(response.body)
        assertEquals(ServerError.VALIDATION_FAILED, response.body?.code)
        assertEquals("Email format is invalid", response.body?.message)
        assertEquals(listOf("Email format is invalid", "Password cannot be blank"), response.body?.errors)
        assertEquals("Email format is invalid", response.body?.fieldErrors?.get("email"))
        assertEquals("Password cannot be blank", response.body?.fieldErrors?.get("password"))
    }

    @Test
    fun `handles InvalidUuidException with 400 status and does not leak invalid value`() {
        val ex = InvalidUuidException(parameterName = "roomId", invalidValue = "not-a-valid-uuid")
        val response = handler.onInvalidUuid(ex)

        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
        assertNotNull(response.body)
        assertEquals(ServerError.BAD_REQUEST, response.body?.code)
        assertEquals("Parameter 'roomId' must be a valid UUID", response.body?.message)
        assertFalse(response.body?.message?.contains("not-a-valid-uuid") == true)
    }

    @Test
    fun `handles MethodArgumentTypeMismatchException with 400 status without echoing invalid value`() {
        val method = javaClass.getDeclaredMethod("dummyMethod", String::class.java)
        val parameter = MethodParameter(method, 0)
        val ex = MethodArgumentTypeMismatchException("raw-value", String::class.java, "filterId", parameter, null)
        val response = handler.onTypeMismatch(ex)

        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
        assertNotNull(response.body)
        assertEquals(ServerError.BAD_REQUEST, response.body?.code)
        assertEquals("Invalid parameter: 'filterId'", response.body?.message)
        assertFalse(response.body?.message?.contains("raw-value") == true)
    }

    @Test
    fun `treats unhandled IllegalArgumentException as 500 internal server error`() {
        val ex = IllegalArgumentException("Unexpected internal illegal argument")
        val response = handler.onUnhandledException(ex)

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.statusCode)
        assertNotNull(response.body)
        assertEquals(ServerError.INTERNAL_SERVER_ERROR, response.body?.code)
        assertEquals("An unexpected error occurred", response.body?.message)
    }

    @Test
    fun `handles ErrorResponse with 5xx status returning generic 500 error response`() {
        val ex = ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Database connection failed")
        val response = handler.onErrorResponse(ex)

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.statusCode)
        assertNotNull(response.body)
        assertEquals(ServerError.INTERNAL_SERVER_ERROR, response.body?.code)
        assertEquals("An unexpected error occurred", response.body?.message)
    }
}
