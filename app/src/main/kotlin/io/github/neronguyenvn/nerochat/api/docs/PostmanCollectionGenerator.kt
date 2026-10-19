package io.github.neronguyenvn.nerochat.api.docs

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.Operation
import io.swagger.v3.oas.models.PathItem
import io.swagger.v3.oas.models.parameters.Parameter
import java.util.UUID

object PostmanCollectionGenerator {

    private val objectMapper = ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)

    fun generate(openApi: OpenAPI, baseUrl: String = "http://localhost:8080"): String {
        val collectionMap = linkedMapOf<String, Any?>()

        // Info
        collectionMap["info"] = mapOf(
            "_postman_id" to UUID.randomUUID().toString(),
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
            responses.add(
                mapOf(
                    "name" to "$statusCode ${apiResponse.description ?: ""}".trim(),
                    "status" to (apiResponse.description ?: statusCode),
                    "code" to (statusCode.toIntOrNull() ?: 200),
                    "_postman_previewlanguage" to "json",
                    "header" to listOf(mapOf("key" to "Content-Type", "value" to "application/json")),
                    "body" to getSampleResponseBody(statusCode, apiResponse.description)
                )
            )
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
        path.contains("/api/chat/rooms/") && path.endsWith("/participants") && method == "POST" -> """
            {
              "userIds": [
                "00000000-0000-0000-0000-000000000000"
              ]
            }
        """.trimIndent()
        else -> null
    }

    private fun getSampleResponseBody(statusCode: String, description: String?): String = when (statusCode) {
        "200", "201" -> """
            {
              "status": "success"
            }
        """.trimIndent()
        "400" -> """
            {
              "code": "VALIDATION_FAILED",
              "message": "Validation failed",
              "errors": ["Email must not be blank"],
              "fieldErrors": { "email": "Email must not be blank" }
            }
        """.trimIndent()
        "401" -> """
            {
              "code": "UNAUTHORIZED",
              "message": "Authentication required"
            }
        """.trimIndent()
        "403" -> """
            {
              "code": "FORBIDDEN",
              "message": "Access denied"
            }
        """.trimIndent()
        "404" -> """
            {
              "code": "CHAT_ROOM_NOT_FOUND",
              "message": "Chat room not found"
            }
        """.trimIndent()
        "409" -> """
            {
              "code": "USER_ALREADY_EXISTS",
              "message": "User with this email already exists"
            }
        """.trimIndent()
        "429" -> """
            {
              "code": "RATE_LIMIT_EXCEEDED",
              "message": "Too many requests. Please try again later.",
              "retryAfterSeconds": 60
            }
        """.trimIndent()
        else -> """
            {
              "code": "INTERNAL_SERVER_ERROR",
              "message": "An unexpected error occurred"
            }
        """.trimIndent()
    }
}
