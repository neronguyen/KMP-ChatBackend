package io.github.neronguyenvn.nerochat.api.security

import io.github.neronguyenvn.nerochat.api.advice.CommonExceptionHandler
import io.github.neronguyenvn.nerochat.api.config.OpenApiConfig
import io.github.neronguyenvn.nerochat.api.validation.ContractRequestValidator
import io.github.neronguyenvn.nerochat.api.validation.ContractValidationAdvice
import io.github.neronguyenvn.nerochat.chat.api.advice.ChatExceptionHandler
import io.github.neronguyenvn.nerochat.chat.api.controller.ChatMessageController
import io.github.neronguyenvn.nerochat.chat.api.controller.ChatParticipantController
import io.github.neronguyenvn.nerochat.chat.api.controller.ChatRoomController
import io.github.neronguyenvn.nerochat.chat.service.ChatMessageService
import io.github.neronguyenvn.nerochat.chat.service.ChatParticipantService
import io.github.neronguyenvn.nerochat.chat.service.ChatRoomService
import io.github.neronguyenvn.nerochat.contract.error.ApiErrorResponse
import io.github.neronguyenvn.nerochat.contract.error.CommonServerError
import io.github.neronguyenvn.nerochat.domain.type.UserId
import io.github.neronguyenvn.nerochat.service.JwtService
import io.github.neronguyenvn.nerochat.user.api.advice.AuthExceptionHandler
import io.github.neronguyenvn.nerochat.user.api.controller.AuthController
import io.github.neronguyenvn.nerochat.user.service.AuthService
import io.github.neronguyenvn.nerochat.user.service.EmailVerificationService
import io.github.neronguyenvn.nerochat.user.service.PasswordResetService
import io.github.neronguyenvn.nerochat.user.service.ratelimiting.EmailRateLimitingService
import jakarta.servlet.Filter
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.access.AccessDeniedException
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import java.util.*

@SpringBootTest(classes = [SecurityAndValidationIntegrationTest.TestConfig::class])
class SecurityAndValidationIntegrationTest {

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
        @Bean
        fun authService(): AuthService = mock(AuthService::class.java)
        @Bean
        fun emailVerificationService(): EmailVerificationService = mock(EmailVerificationService::class.java)
        @Bean
        fun passwordResetService(): PasswordResetService = mock(PasswordResetService::class.java)
        @Bean
        fun emailRateLimit(): EmailRateLimitingService = mock(EmailRateLimitingService::class.java)
        @Bean
        fun chatRoomService(): ChatRoomService = mock(ChatRoomService::class.java)
        @Bean
        fun chatMessageService(): ChatMessageService = mock(ChatMessageService::class.java)
        @Bean
        fun chatParticipantService(): ChatParticipantService = mock(ChatParticipantService::class.java)
        @Bean
        fun jwtService(): JwtService = mock(JwtService::class.java)
        @Bean
        fun redissonClient(): org.redisson.api.RedissonClient = mock(org.redisson.api.RedissonClient::class.java)
        @Bean
        fun jwtAuthenticationFilter(jwtService: JwtService): JwtAuthenticationFilter =
            JwtAuthenticationFilter(jwtService)
    }

    @Autowired
    private lateinit var wac: WebApplicationContext

    @Autowired
    private lateinit var securityConfig: SecurityConfig

    @Autowired
    private lateinit var jwtService: JwtService

    @Autowired
    private lateinit var chatRoomService: ChatRoomService

    @Autowired
    private lateinit var chatMessageService: ChatMessageService

    private lateinit var mockMvc: MockMvc
    private val json = Json { ignoreUnknownKeys = true }
    private val validToken = "valid-test-access-token"
    private val testUserId = UserId(UUID.randomUUID())

    @BeforeEach
    fun setUp() {
        val springSecurityFilterChain = wac.getBean("springSecurityFilterChain", Filter::class.java)
        mockMvc = MockMvcBuilders.webAppContextSetup(wac)
            .addFilter<org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder>(springSecurityFilterChain)
            .build()

        `when`(jwtService.validateAccessToken(validToken)).thenReturn(true)
        `when`(jwtService.getUserIdFromToken(validToken)).thenReturn(testUserId)
    }

    @Test
    fun `unauthenticated access to protected endpoint returns 401 with deserializable ApiErrorResponse`() {
        val result = mockMvc.perform(get("/api/chat/rooms"))
            .andExpect(status().isUnauthorized)
            .andExpect(content().contentType(MediaType.APPLICATION_JSON_VALUE))
            .andReturn()

        val responseBody = result.response.contentAsString
        val apiError = json.decodeFromString<ApiErrorResponse<CommonServerError>>(responseBody)

        assertEquals(CommonServerError.Unauthorized, apiError.error)
        assertEquals("Authentication required", apiError.message)
    }

    @Test
    fun `access denied handler writes 403 with deserializable ApiErrorResponse`() {
        val request = MockHttpServletRequest()
        val response = MockHttpServletResponse()
        val handler = securityConfig.apiAccessDeniedHandler()

        handler.handle(request, response, AccessDeniedException("Access Denied"))

        assertEquals(403, response.status)
        assertEquals(MediaType.APPLICATION_JSON_VALUE, response.contentType)

        val apiError = json.decodeFromString<ApiErrorResponse<CommonServerError>>(response.contentAsString)
        assertEquals(CommonServerError.Forbidden, apiError.error)
        assertEquals("Access denied", apiError.message)
    }

    @Test
    fun `end-to-end invalid request invokes contract validation and returns 400 VALIDATION_FAILED`() {
        val invalidPayload = """
            {
                "email": "not-a-valid-email",
                "displayName": "",
                "password": ""
            }
        """.trimIndent()

        val result = mockMvc.perform(
            post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(invalidPayload)
        )
            .andExpect(status().isBadRequest)
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andReturn()

        val responseBody = result.response.contentAsString
        val apiError = json.decodeFromString<ApiErrorResponse<CommonServerError>>(responseBody)

        assertEquals(CommonServerError.ValidationFailed, apiError.error)
        assertNotNull(apiError.message)
        assertTrue(apiError.fieldErrors.containsKey("email"), "fieldErrors should contain 'email'")
        assertTrue(apiError.fieldErrors.containsKey("displayName"), "fieldErrors should contain 'displayName'")
        assertTrue(apiError.fieldErrors.containsKey("password"), "fieldErrors should contain 'password'")
    }

    @Test
    fun `end-to-end malformed json body returns 400 BAD_REQUEST`() {
        val malformedJson = "{not-valid-json"

        val result = mockMvc.perform(
            post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(malformedJson)
        )
            .andExpect(status().isBadRequest)
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andReturn()

        val responseBody = result.response.contentAsString
        val apiError = json.decodeFromString<ApiErrorResponse<CommonServerError>>(responseBody)

        assertEquals(CommonServerError.BadRequest, apiError.error)
        assertEquals("Malformed request body", apiError.message)
    }

    @Test
    fun `GET room with malformed roomId returns 400 BAD_REQUEST and does not invoke service`() {
        val result = mockMvc.perform(
            get("/api/chat/rooms/not-a-valid-uuid")
                .header("Authorization", "Bearer $validToken")
        )
            .andExpect(status().isBadRequest)
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andReturn()

        val apiError = json.decodeFromString<ApiErrorResponse<CommonServerError>>(result.response.contentAsString)
        assertEquals(CommonServerError.BadRequest, apiError.error)
        assertEquals("Parameter 'roomId' must be a valid UUID", apiError.message)
        verifyNoInteractions(chatRoomService, chatMessageService)
    }

    @Test
    fun `GET messages with malformed roomId returns 400 BAD_REQUEST and does not invoke service`() {
        val result = mockMvc.perform(
            get("/api/chat/rooms/not-a-valid-uuid/messages")
                .header("Authorization", "Bearer $validToken")
        )
            .andExpect(status().isBadRequest)
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andReturn()

        val apiError = json.decodeFromString<ApiErrorResponse<CommonServerError>>(result.response.contentAsString)
        assertEquals(CommonServerError.BadRequest, apiError.error)
        assertEquals("Parameter 'roomId' must be a valid UUID", apiError.message)
        verifyNoInteractions(chatRoomService, chatMessageService)
    }

    @Test
    fun `DELETE leave room with malformed roomId returns 400 BAD_REQUEST and does not invoke service`() {
        val result = mockMvc.perform(
            delete("/api/chat/rooms/not-a-valid-uuid/leave")
                .header("Authorization", "Bearer $validToken")
        )
            .andExpect(status().isBadRequest)
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andReturn()

        val apiError = json.decodeFromString<ApiErrorResponse<CommonServerError>>(result.response.contentAsString)
        assertEquals(CommonServerError.BadRequest, apiError.error)
        assertEquals("Parameter 'roomId' must be a valid UUID", apiError.message)
        verifyNoInteractions(chatRoomService, chatMessageService)
    }

    @Test
    fun `POST add participants with malformed roomId returns 400 BAD_REQUEST and does not invoke service`() {
        val validBody = """{"userIds": ["${UUID.randomUUID()}"]}"""

        val result = mockMvc.perform(
            post("/api/chat/rooms/not-a-valid-uuid/add")
                .header("Authorization", "Bearer $validToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content(validBody)
        )
            .andExpect(status().isBadRequest)
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andReturn()

        val apiError = json.decodeFromString<ApiErrorResponse<CommonServerError>>(result.response.contentAsString)
        assertEquals(CommonServerError.BadRequest, apiError.error)
        assertEquals("Parameter 'roomId' must be a valid UUID", apiError.message)
        verifyNoInteractions(chatRoomService, chatMessageService)
    }

    @Test
    fun `DELETE message with malformed messageId returns 400 BAD_REQUEST and does not invoke service`() {
        val result = mockMvc.perform(
            delete("/api/chat/messages/not-a-valid-uuid")
                .header("Authorization", "Bearer $validToken")
        )
            .andExpect(status().isBadRequest)
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andReturn()

        val apiError = json.decodeFromString<ApiErrorResponse<CommonServerError>>(result.response.contentAsString)
        assertEquals(CommonServerError.BadRequest, apiError.error)
        assertEquals("Parameter 'messageId' must be a valid UUID", apiError.message)
        verifyNoInteractions(chatRoomService, chatMessageService)
    }

    @Test
    fun `POST direct room with malformed targetUserId returns 400 VALIDATION_FAILED and does not invoke service`() {
        val invalidPayload = """
            {
                "targetUserId": "not-a-valid-uuid",
                "message": "Hello"
            }
        """.trimIndent()

        val result = mockMvc.perform(
            post("/api/chat/rooms/direct")
                .header("Authorization", "Bearer $validToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content(invalidPayload)
        )
            .andExpect(status().isBadRequest)
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andReturn()

        val apiError = json.decodeFromString<ApiErrorResponse<CommonServerError>>(result.response.contentAsString)
        assertEquals(CommonServerError.ValidationFailed, apiError.error)
        assertEquals("Target user ID must be a valid UUID", apiError.message)
        assertEquals("Target user ID must be a valid UUID", apiError.fieldErrors["targetUserId"])
        verifyNoInteractions(chatRoomService, chatMessageService)
    }

    @Test
    fun `POST group room with malformed participantIds returns 400 VALIDATION_FAILED and does not invoke service`() {
        val invalidPayload = """
            {
                "name": "Team",
                "participantIds": ["${UUID.randomUUID()}", "not-a-valid-uuid"]
            }
        """.trimIndent()

        val result = mockMvc.perform(
            post("/api/chat/rooms/group")
                .header("Authorization", "Bearer $validToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content(invalidPayload)
        )
            .andExpect(status().isBadRequest)
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andReturn()

        val apiError = json.decodeFromString<ApiErrorResponse<CommonServerError>>(result.response.contentAsString)
        assertEquals(CommonServerError.ValidationFailed, apiError.error)
        assertEquals("Participant ID must be a valid UUID", apiError.message)
        assertEquals("Participant ID must be a valid UUID", apiError.fieldErrors["participantIds"])
        verifyNoInteractions(chatRoomService, chatMessageService)
    }

    @Test
    fun `POST add participants with malformed userIds returns 400 VALIDATION_FAILED and does not invoke service`() {
        val validRoomId = UUID.randomUUID().toString()
        val invalidPayload = """
            {
                "userIds": ["not-a-valid-uuid"]
            }
        """.trimIndent()

        val result = mockMvc.perform(
            post("/api/chat/rooms/$validRoomId/add")
                .header("Authorization", "Bearer $validToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content(invalidPayload)
        )
            .andExpect(status().isBadRequest)
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andReturn()

        val apiError = json.decodeFromString<ApiErrorResponse<CommonServerError>>(result.response.contentAsString)
        assertEquals(CommonServerError.ValidationFailed, apiError.error)
        assertEquals("Participant ID must be a valid UUID", apiError.message)
        assertEquals("Participant ID must be a valid UUID", apiError.fieldErrors["userIds"])
        verifyNoInteractions(chatRoomService, chatMessageService)
    }
}
