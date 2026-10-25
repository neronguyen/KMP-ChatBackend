package io.github.neronguyenvn.nerochat.api.advice

import io.github.neronguyenvn.nerochat.api.validation.InvalidUuidException
import io.github.neronguyenvn.nerochat.contract.error.ApiErrorResponse
import io.github.neronguyenvn.nerochat.contract.error.ServerError
import io.github.neronguyenvn.nerochat.domain.exception.ForbiddenException
import io.github.neronguyenvn.nerochat.domain.exception.UnauthorizedException
import org.slf4j.LoggerFactory
import org.springframework.beans.TypeMismatchException
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.ErrorResponse
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.context.request.WebRequest
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler

/**
 * Fallback global exception handler normalizing all framework and unhandled exceptions
 * into a unified [ApiErrorResponse] contract.
 *
 * Note: Filter-level security exceptions (401/403) are handled upstream in the SecurityFilterChain.
 */
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
class CommonExceptionHandler : ResponseEntityExceptionHandler() {

    private val log = LoggerFactory.getLogger(javaClass)

    // --- 1. APPLICATION-SPECIFIC EXCEPTIONS ---

    @ExceptionHandler(UnauthorizedException::class)
    fun onUnauthorized(e: UnauthorizedException): ResponseEntity<ApiErrorResponse> =
        ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(
            ApiErrorResponse(code = ServerError.UNAUTHORIZED, message = e.message)
        )

    @ExceptionHandler(ForbiddenException::class)
    fun onForbidden(e: ForbiddenException): ResponseEntity<ApiErrorResponse> =
        ResponseEntity.status(HttpStatus.FORBIDDEN).body(
            ApiErrorResponse(code = ServerError.FORBIDDEN, message = e.message ?: "Access denied")
        )

    @ExceptionHandler(InvalidUuidException::class)
    fun onInvalidUuid(e: InvalidUuidException): ResponseEntity<ApiErrorResponse> {
        log.warn("Invalid UUID parameter '{}'", e.parameterName)
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
            ApiErrorResponse(code = ServerError.BAD_REQUEST, message = e.clientMessage)
        )
    }

    // --- 2. STRUCTURED VALIDATION OVERRIDE (REQUIRES FIELD-LEVEL MAP) ---

    override fun handleMethodArgumentNotValid(
        ex: MethodArgumentNotValidException,
        headers: HttpHeaders,
        status: HttpStatusCode,
        request: WebRequest
    ): ResponseEntity<Any> {
        val errors = ex.bindingResult.allErrors.map { it.defaultMessage ?: "Invalid value" }
        val fieldErrors = ex.bindingResult.fieldErrors.associate {
            it.field to (it.defaultMessage ?: "Invalid value")
        }
        val body = ApiErrorResponse(
            code = ServerError.VALIDATION_FAILED,
            message = errors.firstOrNull() ?: "Validation failed",
            errors = errors,
            fieldErrors = fieldErrors
        )
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).headers(headers).body(body)
    }

    // --- 3. CENTRALIZED SPRING MVC FALLBACK (SINGLE POINT OF LOGGING & SANITIZATION) ---

    override fun handleExceptionInternal(
        ex: Exception,
        body: Any?,
        headers: HttpHeaders,
        statusCode: HttpStatusCode,
        request: WebRequest
    ): ResponseEntity<Any>? {
        val isClientError = statusCode.is4xxClientError
        val code = if (isClientError) ServerError.BAD_REQUEST else ServerError.INTERNAL_SERVER_ERROR

        // Sanitize framework error messages: expose parameter names where helpful, never rejected values.
        val message = when (ex) {
            is MissingServletRequestParameterException -> "Missing required parameter: '${ex.parameterName}'"
            is TypeMismatchException -> {
                val paramName = (ex as? MethodArgumentTypeMismatchException)?.name ?: ex.propertyName ?: "unknown"
                "Invalid parameter: '$paramName'"
            }

            is HttpMessageNotReadableException -> "Malformed request body"
            else -> if (isClientError) {
                (ex as? ErrorResponse)?.body?.detail ?: "Invalid request"
            } else {
                "An unexpected error occurred"
            }
        }

        if (isClientError) {
            log.warn("Spring MVC 4xx error ({}): {}", statusCode.value(), ex.message)
        } else {
            log.error("Spring MVC 5xx error ({}): {}", statusCode.value(), ex.message, ex)
        }

        val apiError = ApiErrorResponse(code = code, message = message)
        return ResponseEntity.status(statusCode).headers(headers).body(apiError)
    }

    // --- 4. FINAL CATCH-ALL FOR UNHANDLED RUNTIME EXCEPTIONS ---

    @ExceptionHandler(Exception::class)
    fun onUnhandledException(e: Exception): ResponseEntity<ApiErrorResponse> {
        log.error("Unhandled exception occurred: ${e.message}", e)
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
            ApiErrorResponse(
                code = ServerError.INTERNAL_SERVER_ERROR,
                message = "An unexpected error occurred"
            )
        )
    }
}
