package io.github.neronguyenvn.nerochat.api.advice

import io.github.neronguyenvn.nerochat.contract.error.ApiErrorResponse
import io.github.neronguyenvn.nerochat.contract.error.ServerError
import io.github.neronguyenvn.nerochat.domain.exception.ForbiddenException
import io.github.neronguyenvn.nerochat.domain.exception.UnauthorizedException
import org.slf4j.LoggerFactory
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.validation.FieldError
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException

@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
class CommonExceptionHandler {

    private val logger = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(UnauthorizedException::class)
    fun onUnauthorized(e: UnauthorizedException): ResponseEntity<ApiErrorResponse> =
        ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(
            ApiErrorResponse(
                code = ServerError.UNAUTHORIZED,
                message = e.message,
            )
        )

    @ExceptionHandler(ForbiddenException::class)
    fun onForbidden(e: ForbiddenException): ResponseEntity<ApiErrorResponse> =
        ResponseEntity.status(HttpStatus.FORBIDDEN).body(
            ApiErrorResponse(
                code = ServerError.FORBIDDEN,
                message = e.message ?: "Access denied",
            )
        )

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun onMethodArgumentNotValid(e: MethodArgumentNotValidException): ResponseEntity<ApiErrorResponse> {
        val errors = e.bindingResult.allErrors.map {
            it.defaultMessage ?: "Invalid value"
        }
        val fieldErrors = e.bindingResult.fieldErrors.associate { fieldError: FieldError ->
            fieldError.field to (fieldError.defaultMessage ?: "Invalid value")
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
            ApiErrorResponse(
                code = ServerError.VALIDATION_FAILED,
                message = errors.firstOrNull() ?: "Validation failed",
                errors = errors,
                fieldErrors = fieldErrors,
            )
        )
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun onTypeMismatch(e: MethodArgumentTypeMismatchException): ResponseEntity<ApiErrorResponse> =
        ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
            ApiErrorResponse(
                code = ServerError.BAD_REQUEST,
                message = "Invalid parameter: '${e.name}' has invalid value '${e.value}'",
            )
        )

    @ExceptionHandler(MissingServletRequestParameterException::class)
    fun onMissingParameter(e: MissingServletRequestParameterException): ResponseEntity<ApiErrorResponse> =
        ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
            ApiErrorResponse(
                code = ServerError.BAD_REQUEST,
                message = "Missing required parameter: '${e.parameterName}'",
            )
        )

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun onMessageNotReadable(e: HttpMessageNotReadableException): ResponseEntity<ApiErrorResponse> =
        ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
            ApiErrorResponse(
                code = ServerError.BAD_REQUEST,
                message = "Malformed request body",
            )
        )

    @ExceptionHandler(IllegalArgumentException::class)
    fun onIllegalArgument(e: IllegalArgumentException): ResponseEntity<ApiErrorResponse> =
        ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
            ApiErrorResponse(
                code = ServerError.BAD_REQUEST,
                message = e.message ?: "Invalid request parameters",
            )
        )

    @ExceptionHandler(Exception::class)
    fun onUnhandledException(e: Exception): ResponseEntity<ApiErrorResponse> {
        logger.error("Unhandled exception occurred: ${e.message}", e)
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
            ApiErrorResponse(
                code = ServerError.INTERNAL_SERVER_ERROR,
                message = "An unexpected error occurred",
            )
        )
    }
}
