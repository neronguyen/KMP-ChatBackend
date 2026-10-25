package io.github.neronguyenvn.nerochat.api.advice

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
}
