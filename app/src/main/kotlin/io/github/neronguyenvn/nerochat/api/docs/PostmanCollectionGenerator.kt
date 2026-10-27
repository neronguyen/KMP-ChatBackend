package io.github.neronguyenvn.nerochat.api.docs

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.Operation
import io.swagger.v3.oas.models.PathItem
import io.swagger.v3.oas.models.media.ArraySchema
import io.swagger.v3.oas.models.media.Schema
import io.swagger.v3.oas.models.responses.ApiResponse
import java.util.UUID

object PostmanCollectionGenerator {

    private val objectMapper = ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)

    fun generate(openApi: OpenAPI, baseUrl: String = "http://localhost:8080"): String {
        val collectionMap = linkedMapOf<String, Any?>()

        // Info
        collectionMap["info"] = mapOf(
            "_postman_id" to UUID.nameUUIDFromBytes((openApi.info?.title ?: "NeroChat API").toByteArray()).toString(),
            "name" to (openApi.info?.title ?: "NeroChat API"),
            "description" to (openApi.info?.description ?: "NeroChat REST API Collection generated from OpenAPI specification"),
            "schema" to "https://schema.getpostman.com/json/collection/v2.1.0/collection.json"
        )

        // Variables
        collectionMap["variable"] = listOf(
            mapOf("key" to "baseUrl", "value" to baseUrl, "type" to "string"),
            mapOf("key" to "bearerToken", "value" to "", "type" to "string")
        )

        // Group operations by tag
        val paths = openApi.paths ?: emptyMap<String, PathItem>()
        val taggedItems = linkedMapOf<String, MutableList<Map<String, Any?>>>()

        for ((pathString, pathItem) in paths) {
            val operations = getOperations(pathItem)
            for ((httpMethod, operation) in operations) {
                val tag = operation.tags?.firstOrNull() ?: "General"
                val item = createPostmanItem(pathString, httpMethod, operation, openApi)
                taggedItems.computeIfAbsent(tag) { mutableListOf() }.add(item)
            }
        }

        // Create folders
        val items = taggedItems.map { (tag, folderItems) ->
            mapOf(
                "name" to tag,
                "item" to folderItems
            )
        }
        collectionMap["item"] = items

        return objectMapper.writeValueAsString(collectionMap)
    }

    private fun getOperations(pathItem: PathItem): List<Pair<String, Operation>> {
        val list = mutableListOf<Pair<String, Operation>>()
        pathItem.get?.let { list.add("GET" to it) }
        pathItem.post?.let { list.add("POST" to it) }
        pathItem.put?.let { list.add("PUT" to it) }
        pathItem.delete?.let { list.add("DELETE" to it) }
        pathItem.patch?.let { list.add("PATCH" to it) }
        return list
    }

    private fun createPostmanItem(
        pathString: String,
        httpMethod: String,
        operation: Operation,
        openApi: OpenAPI
    ): Map<String, Any?> {
        val name = operation.summary
            ?: operation.operationId
            ?: "$httpMethod $pathString"

        val request = linkedMapOf<String, Any?>()
        request["method"] = httpMethod

        // Headers
        val headers = mutableListOf<Map<String, String>>()
        if (httpMethod in listOf("POST", "PUT", "PATCH") || operation.requestBody != null) {
            headers.add(mapOf("key" to "Content-Type", "value" to "application/json"))
        }
        request["header"] = headers

        // Body
        val sampleBody = getSampleBody(pathString, httpMethod)
        if (sampleBody != null) {
            request["body"] = mapOf(
                "mode" to "raw",
                "raw" to sampleBody,
                "options" to mapOf("raw" to mapOf("language" to "json"))
            )
        }

        // Auth
        val requiresAuth = operation.security?.any { it.containsKey("bearerAuth") } ?: false
        if (requiresAuth) {
            request["auth"] = mapOf(
                "type" to "bearer",
                "bearer" to listOf(
                    mapOf("key" to "token", "value" to "{{bearerToken}}", "type" to "string")
                )
            )
        }

        // URL
        val postmanPath = pathString.replace(Regex("\\{([^}]+)\\}"), ":$1")
        val pathSegments = postmanPath.trim('/').split('/').filter { it.isNotEmpty() }

        val urlMap = linkedMapOf<String, Any?>()
        urlMap["raw"] = "{{baseUrl}}$postmanPath"
        urlMap["host"] = listOf("{{baseUrl}}")
        urlMap["path"] = pathSegments

        // Query & Path parameters
        val queryParams = mutableListOf<Map<String, String>>()
        val pathVariables = mutableListOf<Map<String, String>>()

        operation.parameters?.forEach { param ->
            when (param.`in`) {
                "query" -> {
                    queryParams.add(
                        mapOf(
                            "key" to param.name,
                            "value" to (param.example?.toString() ?: getSampleParamValue(param.name)),
                            "description" to (param.description ?: "")
                        )
                    )
                }
                "path" -> {
                    pathVariables.add(
                        mapOf(
                            "key" to param.name,
                            "value" to (param.example?.toString() ?: getSampleParamValue(param.name)),
                            "description" to (param.description ?: "")
                        )
                    )
                }
            }
        }

        if (queryParams.isNotEmpty()) {
            urlMap["query"] = queryParams
            val queryStr = queryParams.joinToString("&") { "${it["key"]}=${it["value"]}" }
            urlMap["raw"] = "{{baseUrl}}$postmanPath?$queryStr"
        }
        if (pathVariables.isNotEmpty()) {
            urlMap["variable"] = pathVariables
        }

        request["url"] = urlMap
        request["description"] = operation.description ?: operation.summary ?: ""

        // Example responses
        val responses = mutableListOf<Map<String, Any?>>()
        operation.responses?.forEach { (statusCode, apiResponse) ->
            val code = statusCode.toIntOrNull() ?: 200
            val statusReason = org.springframework.http.HttpStatus.resolve(code)?.reasonPhrase ?: statusCode
            val sampleBody = getSampleResponseBody(statusCode, apiResponse, openApi)
            val responseMap = linkedMapOf<String, Any?>(
                "name" to "$statusCode ${apiResponse.description ?: ""}".trim(),
                "status" to statusReason,
                "code" to code,
                "_postman_previewlanguage" to "json",
                "header" to if (sampleBody != null) {
                    listOf(mapOf("key" to "Content-Type", "value" to "application/json"))
                } else {
                    emptyList<Map<String, String>>()
                }
            )
            if (sampleBody != null) {
                responseMap["body"] = sampleBody
            }
            responses.add(responseMap)
        }

        return mapOf(
            "name" to name,
            "request" to request,
            "response" to responses
        )
    }

    private fun getSampleParamValue(name: String): String = when (name.lowercase()) {
        "email" -> "user@example.com"
        "roomid", "messageid", "targetuserid", "userid" -> "00000000-0000-0000-0000-000000000000"
        "limit" -> "50"
        "before" -> "2026-10-19T00:00:00Z"
        "token" -> "sample-token"
        else -> "string"
    }

    private fun getSampleBody(path: String, method: String): String? = when {
        path.endsWith("/api/auth/register") -> """
            {
              "email": "user@example.com",
              "displayName": "Test User",
              "password": "Password123"
            }
        """.trimIndent()
        path.endsWith("/api/auth/login") -> """
            {
              "email": "user@example.com",
              "password": "Password123"
            }
        """.trimIndent()
        path.endsWith("/api/auth/resend-verification") || path.endsWith("/api/auth/forgot-password") -> """
            {
              "email": "user@example.com"
            }
        """.trimIndent()
        path.endsWith("/api/auth/refresh-token") || path.endsWith("/api/auth/logout") -> """
            {
              "refreshToken": "your-refresh-token"
            }
        """.trimIndent()
        path.endsWith("/api/auth/reset-password") -> """
            {
              "token": "reset-token",
              "newPassword": "NewPassword123"
            }
        """.trimIndent()
        path.endsWith("/api/auth/change-password") -> """
            {
              "oldPassword": "OldPassword123",
              "newPassword": "NewPassword123"
            }
        """.trimIndent()
        path.endsWith("/api/chat/rooms/direct") -> """
            {
              "targetUserId": "00000000-0000-0000-0000-000000000000",
              "message": "Hello!"
            }
        """.trimIndent()
        path.endsWith("/api/chat/rooms/group") -> """
            {
              "name": "Team Chat",
              "participantIds": [
                "00000000-0000-0000-0000-000000000000",
                "11111111-1111-1111-1111-111111111111"
              ]
            }
        """.trimIndent()
        path.contains("/api/chat/rooms/") && (path.endsWith("/participants") || path.endsWith("/add")) && method == "POST" -> """
            {
              "userIds": [
                "00000000-0000-0000-0000-000000000000"
              ]
            }
        """.trimIndent()
        else -> null
    }

    private fun getSampleResponseBody(
        statusCode: String,
        apiResponse: ApiResponse,
        openApi: OpenAPI
    ): String? {
        val content = apiResponse.content
        if (statusCode == "204" || content.isNullOrEmpty()) {
            return null
        }

        if (statusCode.startsWith("2")) {
            val schema = content.values.firstOrNull()?.schema ?: return null
            val sample = generateSampleFromSchema(schema, openApi) ?: return null
            return objectMapper.writeValueAsString(sample)
        }

        val declaredErrorCode = apiResponse.description?.let { desc ->
            Regex("`([A-Z0-9_]+)`").find(desc)?.groupValues?.get(1)
        }
        val errorCode = declaredErrorCode ?: when (statusCode) {
            "400" -> "VALIDATION_FAILED"
            "401" -> "UNAUTHORIZED"
            "403" -> "FORBIDDEN"
            "404" -> "CHAT_ROOM_NOT_FOUND"
            "409" -> "USER_ALREADY_EXISTS"
            "429" -> "RATE_LIMIT_EXCEEDED"
            else -> "INTERNAL_SERVER_ERROR"
        }

        val errorPayload = linkedMapOf<String, Any?>(
            "error" to errorCode,
            "message" to getErrorMessageForCode(errorCode)
        )
        if (errorCode == "VALIDATION_FAILED") {
            errorPayload["fieldErrors"] = mapOf("email" to "Email must not be blank")
        } else if (errorCode == "RATE_LIMIT_EXCEEDED") {
            errorPayload["retryAfterSeconds"] = 60
        }
        return objectMapper.writeValueAsString(errorPayload)
    }

    private fun getErrorMessageForCode(errorCode: String): String = when (errorCode) {
        "VALIDATION_FAILED" -> "Validation failed"
        "BAD_REQUEST" -> "Invalid request"
        "INVALID_CHAT_ROOM_SIZE" -> "Chat room must have at least 2 participants"
        "UNAUTHORIZED" -> "Authentication required"
        "INVALID_CREDENTIALS" -> "Invalid email or password"
        "INVALID_TOKEN" -> "Invalid or expired token"
        "WRONG_PASSWORD" -> "Current password is incorrect"
        "FORBIDDEN" -> "Access denied"
        "EMAIL_NOT_VERIFIED" -> "Email is not verified"
        "USER_NOT_FOUND" -> "User not found"
        "CHAT_ROOM_NOT_FOUND" -> "Chat room not found"
        "CHAT_PARTICIPANT_NOT_FOUND" -> "Chat participant not found"
        "MESSAGE_NOT_FOUND" -> "Message not found"
        "USER_ALREADY_EXISTS" -> "User with this email already exists"
        "SAME_PASSWORD" -> "New password cannot be the same as the old password"
        "PARTICIPANT_ALREADY_IN_ROOM" -> "Participant is already in the chat room"
        "DUPLICATE_MESSAGE_ID" -> "Message with this ID already exists"
        "RATE_LIMIT_EXCEEDED" -> "Too many requests. Please try again later."
        else -> "An unexpected error occurred"
    }

    private fun generateSampleFromSchema(
        schema: Schema<*>,
        openApi: OpenAPI,
        propertyName: String? = null,
        visitedRefs: Set<String> = emptySet()
    ): Any? {
        schema.`$ref`?.let { ref ->
            val refName = ref.substringAfterLast('/')
            if (refName in visitedRefs) return emptyMap<String, Any?>()
            val targetSchema = openApi.components?.schemas?.get(refName) ?: return emptyMap<String, Any?>()
            return generateSampleFromSchema(targetSchema, openApi, propertyName, visitedRefs + refName)
        }

        if (!schema.oneOf.isNullOrEmpty()) {
            val nonNullSubSchema = schema.oneOf.firstOrNull {
                it.`$ref` != null || (it.type != "null" && it.types != setOf("null"))
            }
            if (nonNullSubSchema != null) {
                return generateSampleFromSchema(nonNullSubSchema, openApi, propertyName, visitedRefs)
            }
        }

        if (!schema.anyOf.isNullOrEmpty()) {
            val nonNullSubSchema = schema.anyOf.firstOrNull {
                it.`$ref` != null || (it.type != "null" && it.types != setOf("null"))
            }
            if (nonNullSubSchema != null) {
                return generateSampleFromSchema(nonNullSubSchema, openApi, propertyName, visitedRefs)
            }
        }

        schema.example?.let { return it }
        if (!schema.enum.isNullOrEmpty()) {
            return schema.enum.first()
        }

        val resolvedType = schema.type ?: schema.types?.firstOrNull { it != "null" }

        return when {
            resolvedType == "array" || schema is ArraySchema || schema.items != null -> {
                val itemSample = schema.items?.let { generateSampleFromSchema(it, openApi, propertyName, visitedRefs) }
                if (itemSample != null) listOf(itemSample) else emptyList<Any>()
            }
            resolvedType == "object" || !schema.properties.isNullOrEmpty() -> {
                val result = linkedMapOf<String, Any?>()
                schema.properties?.forEach { (propName, propSchema) ->
                    result[propName] = generateSampleFromSchema(propSchema, openApi, propName, visitedRefs)
                }
                result
            }
            resolvedType == "boolean" -> propertyName != "hasMore"
            resolvedType == "integer" || resolvedType == "number" -> 0
            resolvedType == "string" -> sampleStringForProperty(propertyName, schema.format)
            else -> null
        }
    }

    private fun sampleStringForProperty(propertyName: String?, format: String?): String = when {
        format == "date-time" -> "2026-10-23T18:45:18Z"
        format == "uuid" -> "00000000-0000-0000-0000-000000000000"
        propertyName == null -> "string"
        propertyName.equals("id", ignoreCase = true) || propertyName.endsWith("Id") ->
            "00000000-0000-0000-0000-000000000000"
        propertyName.contains("email", ignoreCase = true) -> "user@example.com"
        propertyName.equals("displayName", ignoreCase = true) -> "Test User"
        propertyName.equals("name", ignoreCase = true) -> "Team Chat"
        propertyName.equals("content", ignoreCase = true) -> "Hello!"
        propertyName.equals("accessToken", ignoreCase = true) -> "sample-access-token"
        propertyName.equals("refreshToken", ignoreCase = true) -> "sample-refresh-token"
        propertyName.contains("url", ignoreCase = true) -> "https://example.com/avatar.png"
        else -> "string"
    }
}
