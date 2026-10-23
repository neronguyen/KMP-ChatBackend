package io.github.neronguyenvn.nerochat.api.advice

import io.github.neronguyenvn.nerochat.contract.error.ServerError
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpStatus
import org.springframework.security.access.AccessDeniedException
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
}
