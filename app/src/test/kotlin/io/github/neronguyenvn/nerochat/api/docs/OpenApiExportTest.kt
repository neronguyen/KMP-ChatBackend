package io.github.neronguyenvn.nerochat.api.docs

import com.fasterxml.jackson.databind.ObjectMapper
import io.github.neronguyenvn.nerochat.api.advice.CommonExceptionHandler
import io.github.neronguyenvn.nerochat.api.config.OpenApiConfig
import io.github.neronguyenvn.nerochat.api.security.JwtAuthenticationFilter
import io.github.neronguyenvn.nerochat.api.security.SecurityConfig
import io.github.neronguyenvn.nerochat.api.validation.ContractRequestValidator
import io.github.neronguyenvn.nerochat.api.validation.ContractValidationAdvice
import io.github.neronguyenvn.nerochat.chat.api.advice.ChatExceptionHandler
import io.github.neronguyenvn.nerochat.chat.api.controller.ChatMessageController
import io.github.neronguyenvn.nerochat.chat.api.controller.ChatParticipantController
import io.github.neronguyenvn.nerochat.chat.api.controller.ChatRoomController
import io.github.neronguyenvn.nerochat.chat.service.ChatMessageService
import io.github.neronguyenvn.nerochat.chat.service.ChatParticipantService
import io.github.neronguyenvn.nerochat.chat.service.ChatRoomService
import io.github.neronguyenvn.nerochat.service.JwtService
import io.github.neronguyenvn.nerochat.user.api.advice.AuthExceptionHandler
import io.github.neronguyenvn.nerochat.user.api.controller.AuthController
import io.github.neronguyenvn.nerochat.user.service.AuthService
import io.github.neronguyenvn.nerochat.user.service.EmailVerificationService
import io.github.neronguyenvn.nerochat.user.service.PasswordResetService
import io.github.neronguyenvn.nerochat.user.service.ratelimiting.EmailRateLimitingService
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springdoc.core.service.OpenAPIService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import java.io.File
import java.util.*

@SpringBootTest(classes = [OpenApiExportTest.TestConfig::class])
class OpenApiExportTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(
        OpenApiConfig::class,
        SecurityConfig::class,
        ContractValidationAdvice::class,
        ContractRequestValidator::class,
        CommonExceptionHandler::class,
        AuthExceptionHandler::class,
        ChatExceptionHandler::class,
        AuthController::class,
        ChatRoomController::class,
        ChatParticipantController::class,
        ChatMessageController::class
    )
    class TestConfig {
        @Bean fun authService(): AuthService = mock(AuthService::class.java)
        @Bean fun emailVerificationService(): EmailVerificationService = mock(EmailVerificationService::class.java)
        @Bean fun passwordResetService(): PasswordResetService = mock(PasswordResetService::class.java)
        @Bean fun emailRateLimit(): EmailRateLimitingService = mock(EmailRateLimitingService::class.java)
        @Bean fun chatRoomService(): ChatRoomService = mock(ChatRoomService::class.java)
        @Bean fun chatMessageService(): ChatMessageService = mock(ChatMessageService::class.java)
        @Bean fun chatParticipantService(): ChatParticipantService = mock(ChatParticipantService::class.java)
        @Bean fun jwtService(): JwtService = mock(JwtService::class.java)
        @Bean fun redissonClient(): org.redisson.api.RedissonClient = mock(org.redisson.api.RedissonClient::class.java)
        @Bean fun jwtAuthenticationFilter(jwtService: JwtService): JwtAuthenticationFilter =
            JwtAuthenticationFilter(jwtService)
    }

    @Autowired
    private lateinit var wac: WebApplicationContext

    @Autowired
    private lateinit var openApiService: OpenAPIService

    private lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).build()
    }

    @Test
    fun `v3 api-docs endpoint returns valid OpenAPI specification with contract errors`() {
        val result = mockMvc.perform(get("/v3/api-docs"))
            .andExpect(status().isOk)
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andReturn()

        val json = result.response.contentAsString
        assertTrue(json.contains("\"title\":\"NeroChat API\""))
        assertTrue(json.contains("\"/api/auth/register\""))
        assertTrue(json.contains("\"/api/auth/login\""))
        assertTrue(json.contains("\"/api/chat/rooms\""))
        assertTrue(json.contains("UserAlreadyExists"))
        assertTrue(json.contains("InvalidCredentials"))
        assertTrue(json.contains("ValidationFailed"))
        assertTrue(json.contains("ApiErrorDto"))
        assertTrue(json.contains("bearerAuth"))
    }

    @Test
    fun `v3 api-docs generates ApiErrorResponse schema conforming to contract constraints`() {
        val result = mockMvc.perform(get("/v3/api-docs"))
            .andExpect(status().isOk)
            .andReturn()

        val rootNode = ObjectMapper().readTree(result.response.contentAsString)
        val schemaNode = rootNode.path("components").path("schemas").path("ApiErrorDto")
        assertTrue(!schemaNode.isMissingNode, "ApiErrorDto schema should be defined under components.schemas")

        // 1. ApiErrorDto properties present
        val properties = schemaNode.path("properties")
        assertTrue(properties.has("error"), "Property 'error' must be present")
        assertTrue(properties.has("message"), "Property 'message' must be present")
        assertTrue(properties.has("fieldErrors"), "Property 'fieldErrors' must be present")
        assertTrue(properties.has("retryAfterSeconds"), "Property 'retryAfterSeconds' must be present")
        assertFalse(properties.has("code"), "Property 'code' should not be present")
        assertFalse(properties.has("errors"), "Property 'errors' should not be present")

        // 2. 'error' and 'message' are required; default-valued fields are optional
        val requiredNode = schemaNode.path("required")
        assertTrue(!requiredNode.isMissingNode && requiredNode.isArray, "'required' array must be present in schema")
        val requiredFields = requiredNode.map { it.asText() }
        assertTrue(requiredFields.contains("error"), "'error' must be in required list")
        assertTrue(requiredFields.contains("message"), "'message' must be in required list")
        assertFalse(requiredFields.contains("fieldErrors"), "'fieldErrors' should not be required")
        assertFalse(requiredFields.contains("retryAfterSeconds"), "'retryAfterSeconds' should not be required")

        // 3. 'message' is not nullable and type is string
        val messageNode = properties.path("message")
        assertEquals("string", messageNode.path("type").asText())
        assertFalse(
            messageNode.has("nullable") && messageNode.path("nullable").asBoolean(),
            "'message' must not be nullable"
        )

        // 4. Schema property types match contract
        assertEquals("object", properties.path("error").path("type").asText())
        assertEquals("object", properties.path("fieldErrors").path("type").asText())
        assertEquals("string", properties.path("fieldErrors").path("additionalProperties").path("type").asText())
        assertEquals("integer", properties.path("retryAfterSeconds").path("type").asText())
        assertEquals("int64", properties.path("retryAfterSeconds").path("format").asText())
    }

    @Test
    fun `swagger-ui html is accessible without authentication`() {
        mockMvc.perform(get("/swagger-ui.html"))
            .andExpect(status().is3xxRedirection)
    }

    @Test
    fun `export openapi specification and postman collection to docs directory`() {
        val docsDir = resolveDocsDir()
        val (openApiJson, postmanCollection) = generateDocsInMemory()

        val openApiFile = File(docsDir, "openapi.json")
        assertTrue(openApiFile.exists(), "Committed docs/openapi.json is missing; run './gradlew :app:exportApiDocs'")
        val objectMapper = ObjectMapper()
        assertEquals(
            objectMapper.readTree(openApiFile),
            objectMapper.readTree(openApiJson),
            "docs/openapi.json is stale; run './gradlew :app:exportApiDocs' to regenerate"
        )

        val postmanFile = File(docsDir, "nerochat.postman_collection.json")
        assertTrue(
            postmanFile.exists(),
            "Committed docs/nerochat.postman_collection.json is missing; run './gradlew :app:exportApiDocs'"
        )
        assertEquals(
            postmanFile.readText().replace("\r\n", "\n"),
            postmanCollection.replace("\r\n", "\n"),
            "docs/nerochat.postman_collection.json is stale; run './gradlew :app:exportApiDocs' to regenerate"
        )
        assertTrue(postmanCollection.contains("NeroChat API"))
        assertTrue(postmanCollection.contains("{{baseUrl}}"))
        assertTrue(postmanCollection.contains("{{bearerToken}}"))
    }

    @Test
    @Tag("export")
    fun `write openapi specification and postman collection to docs directory`() {
        val docsDir = resolveDocsDir()
        val (openApiJson, postmanCollection) = generateDocsInMemory()

        val openApiFile = File(docsDir, "openapi.json")
        openApiFile.writeText(openApiJson)
        assertTrue(openApiFile.exists() && openApiFile.length() > 0)

        val postmanFile = File(docsDir, "nerochat.postman_collection.json")
        postmanFile.writeText(postmanCollection)
        assertTrue(postmanFile.exists() && postmanFile.length() > 0)
    }

    private fun generateDocsInMemory(): Pair<String, String> {
        val openApiJsonResult = mockMvc.perform(get("/v3/api-docs"))
            .andExpect(status().isOk)
            .andReturn()
        val openApiJson = openApiJsonResult.response.contentAsString
        val openApi = openApiService.getCachedOpenAPI(Locale.US) ?: openApiService.build(Locale.US)
        val postmanCollection = PostmanCollectionGenerator.generate(openApi)
        return openApiJson to postmanCollection
    }

    private fun resolveDocsDir(): File {
        val rootDir = System.getProperty("project.rootDir")?.let(::File)
            ?: generateSequence(File(".").canonicalFile) { it.parentFile }
                .firstOrNull { File(it, "settings.gradle.kts").exists() }
            ?: error("Could not locate project root directory containing settings.gradle.kts")
        return File(rootDir, "docs").canonicalFile
    }
}
