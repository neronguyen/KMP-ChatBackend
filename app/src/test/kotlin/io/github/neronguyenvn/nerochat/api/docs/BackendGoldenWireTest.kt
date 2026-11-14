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
import io.github.neronguyenvn.nerochat.chat.api.websocket.ChatWebSocketHandler
import io.github.neronguyenvn.nerochat.chat.api.websocket.WebSocketConfig
import io.github.neronguyenvn.nerochat.chat.domain.model.ChatMessage
import io.github.neronguyenvn.nerochat.chat.domain.model.ChatParticipant
import io.github.neronguyenvn.nerochat.chat.domain.model.ChatRoom
import io.github.neronguyenvn.nerochat.chat.service.ChatMessageService
import io.github.neronguyenvn.nerochat.chat.service.ChatParticipantService
import io.github.neronguyenvn.nerochat.chat.service.ChatRoomService
import io.github.neronguyenvn.nerochat.contract.ContractJson
import io.github.neronguyenvn.nerochat.contract.chat.ChatRoomType
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
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.*
import org.redisson.api.RedissonClient
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
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
        WebSocketConfig::class,
        ChatWebSocketHandler::class,
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
    private lateinit var chatMessageService: ChatMessageService

    @Autowired
    private lateinit var chatWebSocketHandler: ChatWebSocketHandler

    @Autowired
    private lateinit var jwtService: JwtService

    private lateinit var mockMvc: MockMvc

    private val aliceId = "018f3a2b-8a7e-7000-8000-000000000001"
    private val bobId = "018f3a2b-8a7e-7000-8000-000000000002"
    private val roomId = "018f3a2b-8a7e-7000-8000-000000000020"
    private val missingRoomId = "018f3a2b-8a7e-7000-8000-000000000099"
    private val messageId = "018f3a2b-8a7e-7000-8000-000000000010"
    private val fixedInstant = Instant.parse("2026-11-03T20:00:00Z")

    @BeforeEach
    fun setUp() {
        reset(authService, emailVerificationService, chatRoomService, chatMessageService, jwtService)
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

    @Test
    fun `Spring WebSocket handler uses ContractJson for WsClientMessage and WsServerEvent frames`() {
        val validToken = "Bearer valid-access-token"
        val alice = ChatParticipant(
            userId = UserId(aliceId),
            email = "alice@example.com",
            displayName = "Alice",
            profilePictureUrl = null,
        )
        val room = ChatRoom(
            id = ChatRoomId(roomId),
            type = ChatRoomType.DIRECT,
            name = null,
            creator = alice,
            participants = setOf(alice),
            lastMessage = null,
            lastActivityAt = fixedInstant,
            createdAt = fixedInstant,
        )
        val savedMessage = ChatMessage(
            id = ChatMessageId(messageId),
            roomId = ChatRoomId(roomId),
            sender = alice,
            content = "Hello via WS",
            createdAt = fixedInstant,
        )

        `when`(jwtService.validateAccessToken(validToken)).thenReturn(true)
        `when`(jwtService.getUserIdFromToken(validToken)).thenReturn(UserId(aliceId))
        `when`(chatRoomService.findChatRoomsByUser(UserId(aliceId))).thenReturn(listOf(room))
        `when`(
            chatMessageService.sendMessage(
                roomId = ChatRoomId(roomId),
                senderId = UserId(aliceId),
                content = "Hello via WS",
                messageId = ChatMessageId(messageId),
            )
        ).thenReturn(savedMessage)

        val session = mock(WebSocketSession::class.java)
        val headers = HttpHeaders().apply { add(HttpHeaders.AUTHORIZATION, validToken) }
        `when`(session.id).thenReturn("ws-golden-session-1")
        `when`(session.handshakeHeaders).thenReturn(headers)
        `when`(session.isOpen).thenReturn(true)

        chatWebSocketHandler.afterConnectionEstablished(session)
        try {
            val incomingClientJson =
                """{"type":"new_message","roomId":"$roomId","content":"Hello via WS","messageId":"$messageId"}"""
            chatWebSocketHandler.handleMessage(session, TextMessage(incomingClientJson))

            val unknownRoomClientJson =
                """{"type":"new_message","roomId":"$missingRoomId","content":"Unknown room","messageId":"$messageId"}"""
            chatWebSocketHandler.handleMessage(session, TextMessage(unknownRoomClientJson))

            val messageCaptor = ArgumentCaptor.forClass(TextMessage::class.java)
            verify(session, atLeastOnce()).sendMessage(messageCaptor.capture())
            val payloads = messageCaptor.allValues.map { it.payload }

            val expectedBroadcastJson =
                """{"type":"new_message","message":{"id":"$messageId","roomId":"$roomId","senderId":"$aliceId","content":"Hello via WS","createdAt":"2026-11-03T20:00:00Z"}}"""
            val expectedErrorJson =
                """{"type":"error","error":"ChatServerError.ChatRoomNotFound","message":"Chat room not found or access denied","roomId":"$missingRoomId","messageId":"$messageId"}"""

            assertEquals(listOf(expectedBroadcastJson, expectedErrorJson), payloads)
        } finally {
            chatWebSocketHandler.afterConnectionClosed(session, CloseStatus.NORMAL)
        }
    }
}
