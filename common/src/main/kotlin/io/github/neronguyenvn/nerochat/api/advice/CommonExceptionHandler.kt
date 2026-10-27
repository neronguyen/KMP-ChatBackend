package io.github.neronguyenvn.nerochat.api.advice

import io.github.neronguyenvn.nerochat.api.validation.InvalidUuidException
import io.github.neronguyenvn.nerochat.contract.error.ApiErrorResponse
import io.github.neronguyenvn.nerochat.contract.error.CommonServerError
import io.github.neronguyenvn.nerochat.domain.exception.ForbiddenException
import io.github.neronguyenvn.nerochat.domain.exception.UnauthorizedException
import org.slf4j.LoggerFactory
import org.springframework.beans.TypeMismatchException
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.ErrorResponseException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException

/**
 * Fallback global exception handler normalizing all framework and unhandled exceptions
 * into a unified [ApiErrorResponse] contract.
 *
 * Note: Filter-level security exceptions (401/403) are handled upstream in the SecurityFilterChain.
 */
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
class CommonExceptionHandler {

    private val log = LoggerFactory.getLogger(javaClass)

    // --- 1. APPLICATION-SPECIFIC EXCEPTIONS ---

    @ExceptionHandler(UnauthorizedException::class)
    fun onUnauthorized(e: UnauthorizedException): ResponseEntity<ApiErrorResponse<CommonServerError>> =
        ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(
            ApiErrorResponse(error = CommonServerError.Unauthorized, message = e.message ?: "Authentication required")
        )

    @ExceptionHandler(ForbiddenException::class)
    fun onForbidden(e: ForbiddenException): ResponseEntity<ApiErrorResponse<CommonServerError>> =
        ResponseEntity.status(HttpStatus.FORBIDDEN).body(
            ApiErrorResponse(error = CommonServerError.Forbidden, message = e.message ?: "Access denied")
        )

    @ExceptionHandler(InvalidUuidException::class)
    fun onInvalidUuid(e: InvalidUuidException): ResponseEntity<ApiErrorResponse<CommonServerError>> {
        log.warn("Invalid UUID parameter '{}'", e.parameterName)
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
            ApiErrorResponse(error = CommonServerError.BadRequest, message = e.clientMessage)
        )
    }

    // --- 2. STRUCTURED VALIDATION OVERRIDE (REQUIRES FIELD-LEVEL MAP) ---

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun onMethodArgumentNotValid(
        ex: MethodArgumentNotValidException
    ): ResponseEntity<ApiErrorResponse<CommonServerError>> {
        val fieldErrors = ex.bindingResult.fieldErrors.associate {
            it.field to (it.defaultMessage ?: "Invalid value")
        }
        val body: ApiErrorResponse<CommonServerError> = ApiErrorResponse(
            error = CommonServerError.ValidationFailed,
            message = fieldErrors.values.firstOrNull() ?: "Validation failed",
            fieldErrors = fieldErrors
        )
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body)
    }

    // --- 3. CENTRALIZED SPRING MVC FALLBACK (SINGLE POINT OF LOGGING & SANITIZATION) ---

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun onHttpMessageNotReadable(
        ex: HttpMessageNotReadableException
    ): ResponseEntity<ApiErrorResponse<CommonServerError>> {
        log.warn("Spring MVC 4xx error (400): {}", ex.message)
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
            ApiErrorResponse(error = CommonServerError.BadRequest, message = "Malformed request body")
        )
    }

    @ExceptionHandler(MissingServletRequestParameterException::class)
    fun onMissingServletRequestParameter(
        ex: MissingServletRequestParameterException
    ): ResponseEntity<ApiErrorResponse<CommonServerError>> {
        log.warn("Spring MVC 4xx error (400): {}", ex.message)
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
            ApiErrorResponse(
                error = CommonServerError.BadRequest,
                message = "Missing required parameter: '${ex.parameterName}'"
            )
        )
    }

    @ExceptionHandler(TypeMismatchException::class)
    fun onTypeMismatch(
        ex: TypeMismatchException
    ): ResponseEntity<ApiErrorResponse<CommonServerError>> {
        val paramName = (ex as? MethodArgumentTypeMismatchException)?.name ?: ex.propertyName ?: "unknown"
        log.warn("Spring MVC 4xx error (400): {}", ex.message)
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
            ApiErrorResponse(error = CommonServerError.BadRequest, message = "Invalid parameter: '$paramName'")
        )
    }

    @ExceptionHandler(ErrorResponseException::class)
    fun onErrorResponse(
        ex: ErrorResponseException
    ): ResponseEntity<ApiErrorResponse<CommonServerError>> {
        val statusCode = ex.statusCode
        val isClientError = statusCode.is4xxClientError
        val error = if (isClientError) CommonServerError.BadRequest else CommonServerError.InternalServerError
        val message = if (isClientError) ex.body.detail ?: "Invalid request" else "An unexpected error occurred"

        if (isClientError) {
            log.warn("Spring MVC 4xx error ({}): {}", statusCode.value(), ex.message)
        } else {
            log.error("Spring MVC 5xx error ({}): {}", statusCode.value(), ex.message, ex)
        }

        return ResponseEntity.status(statusCode).body(ApiErrorResponse(error = error, message = message))
    }

    // --- 4. FINAL CATCH-ALL FOR UNHANDLED RUNTIME EXCEPTIONS ---

    @ExceptionHandler(Exception::class)
    fun onUnhandledException(e: Exception): ResponseEntity<ApiErrorResponse<CommonServerError>> {
        log.error("Unhandled exception occurred: ${e.message}", e)
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
            ApiErrorResponse(
                error = CommonServerError.InternalServerError,
                message = "An unexpected error occurred"
            )
        )
    }
}
