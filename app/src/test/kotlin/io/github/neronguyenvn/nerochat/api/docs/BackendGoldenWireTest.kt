package io.github.neronguyenvn.nerochat.api.docs

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
import io.github.neronguyenvn.nerochat.chat.domain.model.ChatMessage
import io.github.neronguyenvn.nerochat.chat.domain.model.ChatParticipant
import io.github.neronguyenvn.nerochat.chat.domain.model.ChatRoom
import io.github.neronguyenvn.nerochat.chat.service.ChatMessageService
import io.github.neronguyenvn.nerochat.chat.service.ChatParticipantService
import io.github.neronguyenvn.nerochat.chat.service.ChatRoomService
import io.github.neronguyenvn.nerochat.contract.ContractJson
import io.github.neronguyenvn.nerochat.contract.auth.AuthenticatedUserDto
import io.github.neronguyenvn.nerochat.contract.auth.UserDto
import io.github.neronguyenvn.nerochat.contract.auth.error.AuthServerError
import io.github.neronguyenvn.nerochat.contract.chat.ChatMessageDto
import io.github.neronguyenvn.nerochat.contract.chat.ChatMessageSliceDto
import io.github.neronguyenvn.nerochat.contract.chat.ChatParticipantDto
import io.github.neronguyenvn.nerochat.contract.chat.ChatRoomDto
import io.github.neronguyenvn.nerochat.contract.chat.ChatRoomType
import io.github.neronguyenvn.nerochat.contract.chat.WsClientMessage
import io.github.neronguyenvn.nerochat.contract.chat.WsServerEvent
import io.github.neronguyenvn.nerochat.contract.chat.error.ChatServerError
import io.github.neronguyenvn.nerochat.contract.error.ApiErrorDto
import io.github.neronguyenvn.nerochat.contract.error.CommonServerError
import io.github.neronguyenvn.nerochat.domain.type.ChatMessageId
import io.github.neronguyenvn.nerochat.domain.type.ChatRoomId
import io.github.neronguyenvn.nerochat.domain.type.UserId
import io.github.neronguyenvn.nerochat.service.JwtService
import io.github.neronguyenvn.nerochat.user.api.advice.AuthExceptionHandler
import io.github.neronguyenvn.nerochat.user.api.controller.AuthController
import io.github.neronguyenvn.nerochat.user.domain.exception.InvalidOtpException
import io.github.neronguyenvn.nerochat.user.domain.exception.MaxAttemptsExceededException
import io.github.neronguyenvn.nerochat.user.domain.model.AuthenticatedUser
import io.github.neronguyenvn.nerochat.user.domain.model.User
import io.github.neronguyenvn.nerochat.user.service.AuthService
import io.github.neronguyenvn.nerochat.user.service.EmailVerificationService
import io.github.neronguyenvn.nerochat.user.service.PasswordResetService
import io.github.neronguyenvn.nerochat.user.service.ratelimiting.EmailRateLimitingService
import jakarta.servlet.Filter
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.reset
import org.mockito.Mockito.`when`
import org.redisson.api.RedissonClient
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
@SpringBootTest(classes = [BackendGoldenWireTest.TestConfig::class])
class BackendGoldenWireTest {

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
        ChatMessageController::class,
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
        @Bean fun redissonClient(): RedissonClient = mock(RedissonClient::class.java)
        @Bean fun jwtAuthenticationFilter(jwtService: JwtService): JwtAuthenticationFilter =
            JwtAuthenticationFilter(jwtService)
    }

    @Autowired
    private lateinit var wac: WebApplicationContext

    @Autowired
    private lateinit var configuredJson: Json

    @Autowired
    private lateinit var authService: AuthService

    @Autowired
    private lateinit var emailVerificationService: EmailVerificationService

    @Autowired
    private lateinit var chatRoomService: ChatRoomService

    @Autowired
    private lateinit var jwtService: JwtService

    private lateinit var mockMvc: MockMvc
    private val json = ContractJson

    private val aliceId = "018f3a2b-8a7e-7000-8000-000000000001"
    private val bobId = "018f3a2b-8a7e-7000-8000-000000000002"
    private val roomId = "018f3a2b-8a7e-7000-8000-000000000020"
    private val messageId = "018f3a2b-8a7e-7000-8000-000000000010"
    private val clientTempMessageId = "018f3a2b-8a7e-7000-8000-000000000099"
    private val fixedInstant = Instant.parse("2026-11-03T20:00:00Z")

    @BeforeEach
    fun setUp() {
        reset(authService, emailVerificationService, chatRoomService, jwtService)
        val springSecurityFilterChain = wac.getBean("springSecurityFilterChain", Filter::class.java)
        mockMvc = MockMvcBuilders.webAppContextSetup(wac)
            .addFilter<DefaultMockMvcBuilder>(springSecurityFilterChain)
            .build()
    }

    @Test
    fun `Spring Context uses ContractJson singleton`() {
        assertSame(ContractJson, configuredJson)
    }

    @Test
    fun `ApiErrorDto golden wire parity for CommonServerError and AuthServerError`() {
        val badRequest = ApiErrorDto(
            error = CommonServerError.BadRequest,
            message = "Invalid request",
        )
        val expectedBadRequest =
            """{"error":"CommonServerError.BadRequest","message":"Invalid request","fieldErrors":{}}"""
        assertEquals(expectedBadRequest, json.encodeToString(badRequest))
        assertEquals(badRequest, json.decodeFromString<ApiErrorDto>(expectedBadRequest))

        val validationFailed = ApiErrorDto(
            error = CommonServerError.ValidationFailed,
            message = "Validation failed",
            fieldErrors = mapOf("email" to "Invalid email format"),
            retryAfterSeconds = 60L,
        )
        val expectedValidationFailed =
            """{"error":"CommonServerError.ValidationFailed","message":"Validation failed","fieldErrors":{"email":"Invalid email format"},"retryAfterSeconds":60}"""
        assertEquals(expectedValidationFailed, json.encodeToString(validationFailed))
        assertEquals(validationFailed, json.decodeFromString<ApiErrorDto>(expectedValidationFailed))

        val invalidCode = ApiErrorDto(
            error = AuthServerError.InvalidCode,
            message = "Invalid verification code",
        )
        val expectedInvalidCode =
            """{"error":"AuthServerError.InvalidCode","message":"Invalid verification code","fieldErrors":{}}"""
        assertEquals(expectedInvalidCode, json.encodeToString(invalidCode))
        assertEquals(invalidCode, json.decodeFromString<ApiErrorDto>(expectedInvalidCode))

        val maxAttempts = ApiErrorDto(
            error = AuthServerError.MaxAttemptsExceeded,
            message = "Maximum verification attempts exceeded. Please try again later.",
            retryAfterSeconds = 300L,
        )
        val expectedMaxAttempts =
            """{"error":"AuthServerError.MaxAttemptsExceeded","message":"Maximum verification attempts exceeded. Please try again later.","fieldErrors":{},"retryAfterSeconds":300}"""
        assertEquals(expectedMaxAttempts, json.encodeToString(maxAttempts))
        assertEquals(maxAttempts, json.decodeFromString<ApiErrorDto>(expectedMaxAttempts))
    }

    @Test
    fun `UserDto and ChatParticipantDto golden wire parity with explicitNulls false`() {
        val verifiedUser = UserDto(
            id = aliceId,
            email = "alice@example.com",
            displayName = "Alice",
            isEmailVerified = true,
        )
        val expectedVerifiedUser =
            """{"id":"$aliceId","email":"alice@example.com","displayName":"Alice","isEmailVerified":true}"""
        assertEquals(expectedVerifiedUser, json.encodeToString(verifiedUser))
        assertEquals(verifiedUser, json.decodeFromString<UserDto>(expectedVerifiedUser))

        val unverifiedUser = UserDto(
            id = bobId,
            email = "bob@example.com",
            displayName = "Bob",
            isEmailVerified = false,
        )
        val expectedUnverifiedUser =
            """{"id":"$bobId","email":"bob@example.com","displayName":"Bob","isEmailVerified":false}"""
        assertEquals(expectedUnverifiedUser, json.encodeToString(unverifiedUser))
        assertEquals(unverifiedUser, json.decodeFromString<UserDto>(expectedUnverifiedUser))

        val participantNullAvatar = ChatParticipantDto(
            userId = aliceId,
            email = "alice@example.com",
            displayName = "Alice",
            profilePictureUrl = null,
        )
        val expectedParticipantNullAvatar =
            """{"userId":"$aliceId","email":"alice@example.com","displayName":"Alice"}"""
        assertEquals(expectedParticipantNullAvatar, json.encodeToString(participantNullAvatar))
        assertEquals(
            participantNullAvatar,
            json.decodeFromString<ChatParticipantDto>(expectedParticipantNullAvatar)
        )

        val participantWithAvatar = ChatParticipantDto(
            userId = bobId,
            email = "bob@example.com",
            displayName = "Bob",
            profilePictureUrl = "https://cdn.example.com/bob.png",
        )
        val expectedParticipantWithAvatar =
            """{"userId":"$bobId","email":"bob@example.com","displayName":"Bob","profilePictureUrl":"https://cdn.example.com/bob.png"}"""
        assertEquals(expectedParticipantWithAvatar, json.encodeToString(participantWithAvatar))
        assertEquals(
            participantWithAvatar,
            json.decodeFromString<ChatParticipantDto>(expectedParticipantWithAvatar)
        )
    }

    @Test
    fun `AuthenticatedUserDto golden wire parity`() {
        val dto = AuthenticatedUserDto(
            user = UserDto(
                id = aliceId,
                email = "alice@example.com",
                displayName = "Alice",
                isEmailVerified = true,
            ),
            accessToken = "access-token-abc",
            refreshToken = "refresh-token-xyz",
        )
        val expected =
            """{"user":{"id":"$aliceId","email":"alice@example.com","displayName":"Alice","isEmailVerified":true},"accessToken":"access-token-abc","refreshToken":"refresh-token-xyz"}"""
        assertEquals(expected, json.encodeToString(dto))
        assertEquals(dto, json.decodeFromString<AuthenticatedUserDto>(expected))
    }

    @Test
    fun `ChatRoomDto golden wire parity for DIRECT with lastMessage and GROUP with null name`() {
        val alice = ChatParticipantDto(
            userId = aliceId,
            email = "alice@example.com",
            displayName = "Alice",
            profilePictureUrl = null,
        )
        val bob = ChatParticipantDto(
            userId = bobId,
            email = "bob@example.com",
            displayName = "Bob",
            profilePictureUrl = null,
        )
        val lastMessage = ChatMessageDto(
            id = messageId,
            roomId = roomId,
            senderId = aliceId,
            content = "Golden message",
            createdAt = fixedInstant,
        )

        val directRoom = ChatRoomDto(
            id = roomId,
            type = ChatRoomType.DIRECT,
            name = null,
            creator = alice,
            participants = listOf(alice, bob),
            lastMessage = lastMessage,
            lastActivityAt = fixedInstant,
        )
        val expectedDirect =
            """{"id":"$roomId","type":"DIRECT","creator":{"userId":"$aliceId","email":"alice@example.com","displayName":"Alice"},"participants":[{"userId":"$aliceId","email":"alice@example.com","displayName":"Alice"},{"userId":"$bobId","email":"bob@example.com","displayName":"Bob"}],"lastMessage":{"id":"$messageId","roomId":"$roomId","senderId":"$aliceId","content":"Golden message","createdAt":"2026-11-03T20:00:00Z"},"lastActivityAt":"2026-11-03T20:00:00Z"}"""
        assertEquals(expectedDirect, json.encodeToString(directRoom))
        assertEquals(directRoom, json.decodeFromString<ChatRoomDto>(expectedDirect))

        val groupRoom = ChatRoomDto(
            id = roomId,
            type = ChatRoomType.GROUP,
            name = null,
            creator = alice,
            participants = listOf(alice, bob),
            lastMessage = null,
            lastActivityAt = fixedInstant,
        )
        val expectedGroup =
            """{"id":"$roomId","type":"GROUP","creator":{"userId":"$aliceId","email":"alice@example.com","displayName":"Alice"},"participants":[{"userId":"$aliceId","email":"alice@example.com","displayName":"Alice"},{"userId":"$bobId","email":"bob@example.com","displayName":"Bob"}],"lastActivityAt":"2026-11-03T20:00:00Z"}"""
        assertEquals(expectedGroup, json.encodeToString(groupRoom))
        assertEquals(groupRoom, json.decodeFromString<ChatRoomDto>(expectedGroup))
    }

    @Test
    fun `ChatMessageSliceDto golden wire parity`() {
        val msg = ChatMessageDto(
            id = messageId,
            roomId = roomId,
            senderId = aliceId,
            content = "Golden message",
            createdAt = fixedInstant,
        )
        val slice = ChatMessageSliceDto(
            messages = listOf(msg),
            hasMore = true,
        )
        val expected =
            """{"messages":[{"id":"$messageId","roomId":"$roomId","senderId":"$aliceId","content":"Golden message","createdAt":"2026-11-03T20:00:00Z"}],"hasMore":true}"""
        assertEquals(expected, json.encodeToString(slice))
        assertEquals(slice, json.decodeFromString<ChatMessageSliceDto>(expected))
    }

    @Test
    fun `WsClientMessage and WsServerEvent golden wire parity for all variants`() {
        val clientNewMessage: WsClientMessage = WsClientMessage.NewMessage(
            roomId = roomId,
            content = "Hello via WS",
            messageId = clientTempMessageId,
        )
        val expectedClientNewMessage =
            """{"type":"new_message","roomId":"$roomId","content":"Hello via WS","messageId":"$clientTempMessageId"}"""
        assertEquals(expectedClientNewMessage, json.encodeToString<WsClientMessage>(clientNewMessage))
        assertEquals(clientNewMessage, json.decodeFromString<WsClientMessage>(expectedClientNewMessage))

        val chatMessage = ChatMessageDto(
            id = messageId,
            roomId = roomId,
            senderId = aliceId,
            content = "Broadcast message",
            createdAt = fixedInstant,
        )
        val alice = ChatParticipantDto(
            userId = aliceId,
            email = "alice@example.com",
            displayName = "Alice",
            profilePictureUrl = null,
        )
        val bob = ChatParticipantDto(
            userId = bobId,
            email = "bob@example.com",
            displayName = "Bob",
            profilePictureUrl = null,
        )

        val eventNewMessage: WsServerEvent = WsServerEvent.NewMessage(message = chatMessage)
        val expectedNewMessage =
            """{"type":"new_message","message":{"id":"$messageId","roomId":"$roomId","senderId":"$aliceId","content":"Broadcast message","createdAt":"2026-11-03T20:00:00Z"}}"""
        assertEquals(expectedNewMessage, json.encodeToString<WsServerEvent>(eventNewMessage))
        assertEquals(eventNewMessage, json.decodeFromString<WsServerEvent>(expectedNewMessage))

        val eventDeleted: WsServerEvent = WsServerEvent.MessageDeleted(
            roomId = roomId,
            messageId = messageId,
        )
        val expectedDeleted =
            """{"type":"message_deleted","roomId":"$roomId","messageId":"$messageId"}"""
        assertEquals(expectedDeleted, json.encodeToString<WsServerEvent>(eventDeleted))
        assertEquals(eventDeleted, json.decodeFromString<WsServerEvent>(expectedDeleted))

        val eventJoinedWithAddedBy: WsServerEvent = WsServerEvent.ParticipantJoined(
            roomId = roomId,
            newUsers = listOf(bob),
            addedBy = alice,
        )
        val expectedJoined =
            """{"type":"participant_joined","roomId":"$roomId","newUsers":[{"userId":"$bobId","email":"bob@example.com","displayName":"Bob"}],"addedBy":{"userId":"$aliceId","email":"alice@example.com","displayName":"Alice"}}"""
        assertEquals(expectedJoined, json.encodeToString<WsServerEvent>(eventJoinedWithAddedBy))
        assertEquals(eventJoinedWithAddedBy, json.decodeFromString<WsServerEvent>(expectedJoined))

        val eventLeft: WsServerEvent = WsServerEvent.ParticipantLeft(
            roomId = roomId,
            leftUser = bob,
            kickedBy = null,
        )
        val expectedLeft =
            """{"type":"participant_left","roomId":"$roomId","leftUser":{"userId":"$bobId","email":"bob@example.com","displayName":"Bob"}}"""
        assertEquals(expectedLeft, json.encodeToString<WsServerEvent>(eventLeft))
        assertEquals(eventLeft, json.decodeFromString<WsServerEvent>(expectedLeft))

        val eventError: WsServerEvent = WsServerEvent.Error(
            error = ChatServerError.ChatRoomNotFound,
            message = "Room not found",
            roomId = roomId,
            messageId = messageId,
        )
        val expectedError =
            """{"type":"error","error":"ChatServerError.ChatRoomNotFound","message":"Room not found","roomId":"$roomId","messageId":"$messageId"}"""
        assertEquals(expectedError, json.encodeToString<WsServerEvent>(eventError))
        assertEquals(eventError, json.decodeFromString<WsServerEvent>(expectedError))
    }

    @Test
    fun `Spring MVC serializes RestController payloads and flat ErrorCode identically to ContractJson`() {
        val registerRequestBody = """{"email":"alice@example.com","displayName":"Alice","password":"Password123!"}"""
        `when`(authService.register("alice@example.com", "Alice", "Password123!")).thenReturn(
            User(
                id = UserId(aliceId),
                email = "alice@example.com",
                displayName = "Alice",
                isEmailVerified = false,
            )
        )

        val expectedRegisterJson =
            """{"id":"$aliceId","email":"alice@example.com","displayName":"Alice","isEmailVerified":false}"""
        mockMvc.perform(
            post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(registerRequestBody)
        )
            .andExpect(status().isOk)
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(content().string(expectedRegisterJson))

        val loginRequestBody = """{"email":"alice@example.com","password":"Password123!"}"""
        `when`(authService.login("alice@example.com", "Password123!")).thenReturn(
            AuthenticatedUser(
                user = User(
                    id = UserId(aliceId),
                    email = "alice@example.com",
                    displayName = "Alice",
                    isEmailVerified = true,
                ),
                accessToken = "access-token-abc",
                refreshToken = "refresh-token-xyz",
            )
        )

        val expectedLoginJson =
            """{"user":{"id":"$aliceId","email":"alice@example.com","displayName":"Alice","isEmailVerified":true},"accessToken":"access-token-abc","refreshToken":"refresh-token-xyz"}"""
        mockMvc.perform(
            post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(loginRequestBody)
        )
            .andExpect(status().isOk)
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(content().string(expectedLoginJson))

        val validToken = "valid-access-token"
        `when`(jwtService.validateAccessToken(validToken)).thenReturn(true)
        `when`(jwtService.getUserIdFromToken(validToken)).thenReturn(UserId(aliceId))
        val alice = ChatParticipant(
            userId = UserId(aliceId),
            email = "alice@example.com",
            displayName = "Alice",
            profilePictureUrl = null,
        )
        val bob = ChatParticipant(
            userId = UserId(bobId),
            email = "bob@example.com",
            displayName = "Bob",
            profilePictureUrl = null,
        )
        `when`(chatRoomService.getChatRoomById(ChatRoomId(roomId), UserId(aliceId))).thenReturn(
            ChatRoom(
                id = ChatRoomId(roomId),
                type = ChatRoomType.DIRECT,
                name = null,
                creator = alice,
                participants = setOf(alice, bob),
                lastMessage = ChatMessage(
                    id = ChatMessageId(messageId),
                    roomId = ChatRoomId(roomId),
                    sender = alice,
                    content = "Golden message",
                    createdAt = fixedInstant,
                ),
                lastActivityAt = fixedInstant,
                createdAt = fixedInstant,
            )
        )

        val expectedRoomJson =
            """{"id":"$roomId","type":"DIRECT","creator":{"userId":"$aliceId","email":"alice@example.com","displayName":"Alice"},"participants":[{"userId":"$aliceId","email":"alice@example.com","displayName":"Alice"},{"userId":"$bobId","email":"bob@example.com","displayName":"Bob"}],"lastMessage":{"id":"$messageId","roomId":"$roomId","senderId":"$aliceId","content":"Golden message","createdAt":"2026-11-03T20:00:00Z"},"lastActivityAt":"2026-11-03T20:00:00Z"}"""
        mockMvc.perform(
            get("/api/chat/rooms/$roomId")
                .header("Authorization", "Bearer $validToken")
        )
            .andExpect(status().isOk)
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(content().string(expectedRoomJson))
    }

    @Test
    fun `Spring MVC serializes ApiErrorDto with flat ErrorCode string`() {
        val verifyBody = """{"email":"alice@example.com","code":"000000"}"""

        doThrow(InvalidOtpException())
            .`when`(emailVerificationService)
            .verifyEmail("alice@example.com", "000000")

        val expectedInvalidCodeJson =
            """{"error":"AuthServerError.InvalidCode","message":"Invalid verification code","fieldErrors":{}}"""
        mockMvc.perform(
            post("/api/auth/verify-email")
                .contentType(MediaType.APPLICATION_JSON)
                .content(verifyBody)
        )
            .andExpect(status().isBadRequest)
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(content().string(expectedInvalidCodeJson))

        doThrow(MaxAttemptsExceededException(retryAfterSeconds = 300L))
            .`when`(emailVerificationService)
            .verifyEmail("alice@example.com", "000000")

        val expectedMaxAttemptsJson =
            """{"error":"AuthServerError.MaxAttemptsExceeded","message":"Maximum verification attempts exceeded. Please try again later.","fieldErrors":{},"retryAfterSeconds":300}"""
        mockMvc.perform(
            post("/api/auth/verify-email")
                .contentType(MediaType.APPLICATION_JSON)
                .content(verifyBody)
        )
            .andExpect(status().isTooManyRequests)
            .andExpect(header().string("Retry-After", "300"))
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(content().string(expectedMaxAttemptsJson))
    }
}
