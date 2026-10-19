package io.github.neronguyenvn.nerochat.api.config

import io.github.neronguyenvn.nerochat.api.annotation.ApiErrors
import io.github.neronguyenvn.nerochat.contract.auth.error.AuthServerError
import io.github.neronguyenvn.nerochat.contract.chat.error.ChatServerError
import io.github.neronguyenvn.nerochat.contract.error.ServerError
import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.Operation
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.media.ArraySchema
import io.swagger.v3.oas.models.media.Content
import io.swagger.v3.oas.models.media.IntegerSchema
import io.swagger.v3.oas.models.media.MediaType
import io.swagger.v3.oas.models.media.ObjectSchema
import io.swagger.v3.oas.models.media.Schema
import io.swagger.v3.oas.models.media.StringSchema
import io.swagger.v3.oas.models.responses.ApiResponse
import io.swagger.v3.oas.models.responses.ApiResponses
import io.swagger.v3.oas.models.security.SecurityRequirement
import io.swagger.v3.oas.models.security.SecurityScheme
import org.springdoc.core.customizers.OperationCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.method.HandlerMethod
import kotlin.reflect.KClass

@Configuration
class OpenApiConfig {

    companion object {
        const val SECURITY_SCHEME_NAME = "bearerAuth"
        const val API_ERROR_RESPONSE_SCHEMA = "ApiErrorResponse"

        fun resolveServerErrors(clazz: KClass<out ServerError>): List<ServerError> {
            val instance = clazz.objectInstance
            if (instance != null) return listOf(instance)

            val authMatches = AuthServerError.entries.filter { clazz.java.isAssignableFrom(it::class.java) }
            val chatMatches = ChatServerError.entries.filter { clazz.java.isAssignableFrom(it::class.java) }
            val combined = (authMatches + chatMatches).distinct()
            if (combined.isNotEmpty()) return combined

            if (clazz.isSealed) {
                return clazz.sealedSubclasses.flatMap { resolveServerErrors(it) }.distinct()
            }
            return emptyList()
        }

        fun serverErrorToHttpStatus(error: ServerError): HttpStatus = when (error) {
            is AuthServerError.UserAlreadyExists -> HttpStatus.CONFLICT
            is AuthServerError.InvalidCredentials -> HttpStatus.UNAUTHORIZED
            is AuthServerError.EmailNotVerified -> HttpStatus.FORBIDDEN
            is AuthServerError.InvalidToken -> HttpStatus.UNAUTHORIZED
            is AuthServerError.UserNotFound -> HttpStatus.NOT_FOUND
            is AuthServerError.WrongPassword -> HttpStatus.UNAUTHORIZED
            is AuthServerError.SamePassword -> HttpStatus.CONFLICT
            is AuthServerError.Unauthorized -> HttpStatus.UNAUTHORIZED
            is AuthServerError.RateLimitExceeded -> HttpStatus.TOO_MANY_REQUESTS
            is AuthServerError.ValidationFailed -> HttpStatus.BAD_REQUEST

            is ChatServerError.ChatRoomNotFound -> HttpStatus.NOT_FOUND
            is ChatServerError.ChatParticipantNotFound -> HttpStatus.NOT_FOUND
            is ChatServerError.MessageNotFound -> HttpStatus.NOT_FOUND
            is ChatServerError.InvalidChatRoomSize -> HttpStatus.BAD_REQUEST
            is ChatServerError.DuplicateMessageId -> HttpStatus.CONFLICT
            is ChatServerError.ParticipantAlreadyInRoom -> HttpStatus.CONFLICT
            is ChatServerError.Forbidden -> HttpStatus.FORBIDDEN
            is ChatServerError.ValidationFailed -> HttpStatus.BAD_REQUEST
            is ChatServerError.Unauthorized -> HttpStatus.UNAUTHORIZED
            is ChatServerError.BadRequest -> HttpStatus.BAD_REQUEST
            is ChatServerError.InvalidJson -> HttpStatus.BAD_REQUEST
            is ChatServerError.InvalidPayload -> HttpStatus.BAD_REQUEST
            is ChatServerError.MessageSendFailed -> HttpStatus.INTERNAL_SERVER_ERROR

            else -> when (error.code) {
                ServerError.BAD_REQUEST, ServerError.VALIDATION_FAILED -> HttpStatus.BAD_REQUEST
                ServerError.UNAUTHORIZED -> HttpStatus.UNAUTHORIZED
                ServerError.FORBIDDEN -> HttpStatus.FORBIDDEN
                ServerError.INTERNAL_SERVER_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR
                else -> HttpStatus.BAD_REQUEST
            }
        }
    }

    @Bean
    fun openAPI(): OpenAPI {
        return OpenAPI()
            .info(
                Info()
                    .title("NeroChat API")
                    .description("REST API and WebSocket error contracts for NeroChat messaging backend")
                    .version("1.0.0")
            )
            .components(
                Components()
                    .addSecuritySchemes(
                        SECURITY_SCHEME_NAME,
                        SecurityScheme()
                            .name(SECURITY_SCHEME_NAME)
                            .type(SecurityScheme.Type.HTTP)
                            .scheme("bearer")
                            .bearerFormat("JWT")
                            .description("JWT access token")
                    )
                    .addSchemas(API_ERROR_RESPONSE_SCHEMA, createApiErrorResponseSchema())
            )
    }

    @Bean
    fun apiErrorsOperationCustomizer(): OperationCustomizer =
        OperationCustomizer { operation, handlerMethod ->
            customizeApiErrors(operation, handlerMethod)
            customizeSecurity(operation, handlerMethod)
            operation
        }

    private fun customizeApiErrors(operation: Operation, handlerMethod: HandlerMethod) {
        val apiErrors = handlerMethod.getMethodAnnotation(ApiErrors::class.java) ?: return
        val serverErrors = apiErrors.errors.flatMap { resolveServerErrors(it) }.distinct()
        if (serverErrors.isEmpty()) return

        val groupedByStatus = serverErrors.groupBy { serverErrorToHttpStatus(it) }
        val responses = operation.responses ?: ApiResponses().also { operation.responses = it }

        for ((status, errors) in groupedByStatus) {
            val statusCode = status.value().toString()
            val errorCodes = errors.map { it.code }.distinct()
            val codesFormatted = errorCodes.joinToString(", ") { "`$it`" }
            val description = "${status.reasonPhrase}. Contract error codes: $codesFormatted"

            val existingResponse = responses[statusCode]
            val response = existingResponse ?: ApiResponse()
            response.description = description

            val content = response.content ?: Content()
            if (!content.containsKey(org.springframework.http.MediaType.APPLICATION_JSON_VALUE)) {
                content.addMediaType(
                    org.springframework.http.MediaType.APPLICATION_JSON_VALUE,
                    MediaType().schema(Schema<Any>().`$ref`("#/components/schemas/$API_ERROR_RESPONSE_SCHEMA"))
                )
            }
            response.content = content
            responses.addApiResponse(statusCode, response)
        }
    }

    private fun customizeSecurity(operation: Operation, handlerMethod: HandlerMethod) {
        val classMapping = handlerMethod.beanType.getAnnotation(RequestMapping::class.java)?.value?.firstOrNull() ?: ""
        val isAuthEndpoint = classMapping.startsWith("/api/auth")
        val isChangePassword = handlerMethod.method.name == "changePassword"

        val requiresAuth = !isAuthEndpoint || isChangePassword

        if (requiresAuth) {
            operation.security = listOf(SecurityRequirement().addList(SECURITY_SCHEME_NAME))
        } else {
            operation.security = emptyList()
        }
    }

    private fun createApiErrorResponseSchema(): Schema<Any> {
        val schema = ObjectSchema().apply {
            name = API_ERROR_RESPONSE_SCHEMA
            description = "Standardized error response payload returned on all API error conditions"
            addProperty(
                "code",
                StringSchema()
                    .description("Contract error code identifying the exact error condition")
                    .example("VALIDATION_FAILED")
            )
            addProperty(
                "message",
                StringSchema()
                    .nullable(true)
                    .description("Human-readable error description")
                    .example("Validation failed")
            )
            addProperty(
                "errors",
                ArraySchema()
                    .items(StringSchema())
                    .description("List of error messages")
            )
            addProperty(
                "fieldErrors",
                ObjectSchema()
                    .additionalProperties(StringSchema())
                    .description("Map of request field names to their specific validation failure messages")
            )
            addProperty(
                "retryAfterSeconds",
                IntegerSchema()
                    .format("int64")
                    .nullable(true)
                    .description("Seconds to wait before retrying (included on 429 RATE_LIMIT_EXCEEDED)")
            )
        }
        return schema
    }
}
