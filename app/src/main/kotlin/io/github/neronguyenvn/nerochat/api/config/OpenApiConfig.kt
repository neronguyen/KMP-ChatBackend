package io.github.neronguyenvn.nerochat.api.config

import io.github.neronguyenvn.nerochat.api.annotation.ApiErrors
import io.github.neronguyenvn.nerochat.contract.auth.error.AuthServerError
import io.github.neronguyenvn.nerochat.contract.chat.error.ChatServerError
import io.github.neronguyenvn.nerochat.contract.error.CommonServerError
import io.github.neronguyenvn.nerochat.contract.error.ServerError
import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.Operation
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.media.*
import io.swagger.v3.oas.models.responses.ApiResponse
import io.swagger.v3.oas.models.responses.ApiResponses
import io.swagger.v3.oas.models.security.SecurityRequirement
import io.swagger.v3.oas.models.security.SecurityScheme
import org.springdoc.core.customizers.OperationCustomizer
import org.springdoc.core.utils.SpringDocUtils
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.method.HandlerMethod
import kotlin.reflect.KClass
import kotlin.time.Instant

@Configuration
class OpenApiConfig {

    init {
        SpringDocUtils.getConfig().replaceWithSchema(
            Instant::class.java,
            StringSchema().format("date-time").example("2026-10-23T18:45:18Z")
        )
    }

    companion object {
        const val SECURITY_SCHEME_NAME = "bearerAuth"
        const val API_ERROR_RESPONSE_SCHEMA = "ApiErrorResponse"

        fun resolveServerErrors(clazz: KClass<out ServerError>): List<ServerError> {
            val instance = clazz.objectInstance
            if (instance != null) return listOf(instance)

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

            is ChatServerError.ChatRoomNotFound -> HttpStatus.NOT_FOUND
            is ChatServerError.ChatParticipantNotFound -> HttpStatus.NOT_FOUND
            is ChatServerError.MessageNotFound -> HttpStatus.NOT_FOUND
            is ChatServerError.InvalidChatRoomSize -> HttpStatus.BAD_REQUEST
            is ChatServerError.DuplicateMessageId -> HttpStatus.CONFLICT
            is ChatServerError.ParticipantAlreadyInRoom -> HttpStatus.CONFLICT
            is ChatServerError.ParticipantNotInRoom -> HttpStatus.FORBIDDEN
            is ChatServerError.NotMessageSender -> HttpStatus.FORBIDDEN
            is ChatServerError.InvalidJson -> HttpStatus.BAD_REQUEST
            is ChatServerError.InvalidPayload -> HttpStatus.BAD_REQUEST
            is ChatServerError.MessageSendFailed -> HttpStatus.INTERNAL_SERVER_ERROR

            is CommonServerError.BadRequest, is CommonServerError.ValidationFailed -> HttpStatus.BAD_REQUEST
            is CommonServerError.Unauthorized -> HttpStatus.UNAUTHORIZED
            is CommonServerError.RateLimitExceeded -> HttpStatus.TOO_MANY_REQUESTS
            is CommonServerError.InternalServerError -> HttpStatus.INTERNAL_SERVER_ERROR
            else -> HttpStatus.BAD_REQUEST
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
            customizeParameters(operation)
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
            val errorCodes = errors.map { it::class.simpleName ?: it.toString() }.distinct()
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

    private fun customizeParameters(operation: Operation) {
        operation.parameters?.forEach { parameter ->
            if (parameter.`in` == "path" && parameter.name in listOf("roomId", "messageId", "userId")) {
                parameter.schema = StringSchema().format("uuid")
            }
        }
    }

    private fun createApiErrorResponseSchema(): Schema<Any> {
        val schema = ObjectSchema().apply {
            name = API_ERROR_RESPONSE_SCHEMA
            description = "Standardized error response payload returned on all API error conditions"
            required = listOf("error", "message")
            addProperty(
                "error",
                ObjectSchema()
                    .description("Contract error object or discriminator identifying the exact error condition")
                    .example("ValidationFailed")
            )
            addProperty(
                "message",
                StringSchema()
                    .description("Human-readable error description")
                    .example("Validation failed")
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
