package io.github.neronguyenvn.nerochat.chat.api.websocket

import io.github.neronguyenvn.nerochat.chat.domain.exception.DuplicateMessageIdException
import io.github.neronguyenvn.nerochat.chat.domain.model.ChatMessage
import io.github.neronguyenvn.nerochat.chat.domain.model.ChatParticipant
import io.github.neronguyenvn.nerochat.chat.domain.model.ChatRoom
import io.github.neronguyenvn.nerochat.chat.service.ChatMessageService
import io.github.neronguyenvn.nerochat.chat.service.ChatRoomService
import io.github.neronguyenvn.nerochat.contract.chat.ChatRoomType
import io.github.neronguyenvn.nerochat.contract.chat.WsClientMessage
import io.github.neronguyenvn.nerochat.contract.chat.WsServerEvent
import io.github.neronguyenvn.nerochat.contract.chat.error.ChatServerError
import io.github.neronguyenvn.nerochat.domain.type.ChatMessageId
import io.github.neronguyenvn.nerochat.domain.type.ChatRoomId
import io.github.neronguyenvn.nerochat.domain.type.UserId
import io.github.neronguyenvn.nerochat.service.JwtService
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.*
import org.springframework.http.HttpHeaders
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import java.util.*
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
class ChatWebSocketHandlerTest {

    private lateinit var chatRoomService: ChatRoomService
    private lateinit var chatMessageService: ChatMessageService
    private lateinit var jwtService: JwtService
    private lateinit var handler: ChatWebSocketHandler
    private lateinit var session: WebSocketSession

    private val json = Json {
        classDiscriminator = "type"
    }

    private val userId = UserId(UUID.randomUUID())
    private val roomId = ChatRoomId(UUID.randomUUID())
    private val token = "Bearer valid-token"

    private val participant = ChatParticipant(
        userId = userId,
        email = "user@example.com",
        displayName = "User",
        profilePictureUrl = null
    )

    @BeforeEach
    fun setUp() {
        chatRoomService = mock(ChatRoomService::class.java)
        chatMessageService = mock(ChatMessageService::class.java)
        jwtService = mock(JwtService::class.java)
        session = mock(WebSocketSession::class.java)

        handler = ChatWebSocketHandler(
            chatRoomService = chatRoomService,
            chatMessageService = chatMessageService,
            jwtService = jwtService,
            json = json
        )

        val headers = HttpHeaders().apply {
            add(HttpHeaders.AUTHORIZATION, token)
        }
        `when`(session.id).thenReturn("session-1")
        `when`(session.handshakeHeaders).thenReturn(headers)
        `when`(session.isOpen).thenReturn(true)
        `when`(jwtService.validateAccessToken(token)).thenReturn(true)
        `when`(jwtService.getUserIdFromToken(token)).thenReturn(userId)

        val now = Clock.System.now()
        val room = ChatRoom(
            id = roomId,
            type = ChatRoomType.DIRECT,
            name = null,
            creator = participant,
            participants = setOf(participant),
            lastMessage = null,
            lastActivityAt = now,
            createdAt = now
        )
        `when`(chatRoomService.findChatRoomsByUser(userId)).thenReturn(listOf(room))

        handler.afterConnectionEstablished(session)
    }

    @Test
    fun `handleTextMessage broadcasts new_message with preserved client-assigned messageId`() {
        val clientMessageId = ChatMessageId(UUID.randomUUID().toString())
        val savedMessage = ChatMessage(
            id = clientMessageId,
            roomId = roomId,
            sender = participant,
            content = "Hello from Android",
            createdAt = Clock.System.now()
        )

        `when`(
            chatMessageService.sendMessage(
                roomId = roomId,
                senderId = userId,
                content = "Hello from Android",
                messageId = clientMessageId
            )
        ).thenReturn(savedMessage)

        val incomingPayload = json.encodeToString<WsClientMessage>(
            WsClientMessage.NewMessage(
                roomId = roomId.value,
                content = "Hello from Android",
                messageId = clientMessageId.value
            )
        )

        handler.handleMessage(session, TextMessage(incomingPayload))

        val messageCaptor = ArgumentCaptor.forClass(TextMessage::class.java)
        verify(session).sendMessage(messageCaptor.capture())

        val event = json.decodeFromString<WsServerEvent>(messageCaptor.value.payload)
        assertTrue(event is WsServerEvent.NewMessage)
        val newMessage = event as WsServerEvent.NewMessage
        assertEquals(clientMessageId.value, newMessage.message.id)
        assertEquals(roomId.value, newMessage.message.roomId)
        assertEquals("Hello from Android", newMessage.message.content)
    }

    @Test
    fun `handleTextMessage returns INVALID_JSON when payload is malformed JSON`() {
        handler.handleMessage(session, TextMessage("{not-valid-json"))

        val messageCaptor = ArgumentCaptor.forClass(TextMessage::class.java)
        verify(session).sendMessage(messageCaptor.capture())

        val event = json.decodeFromString<WsServerEvent>(messageCaptor.value.payload)
        assertTrue(event is WsServerEvent.Error)
        assertEquals(ChatServerError.InvalidJson, (event as WsServerEvent.Error).error)
    }

    @Test
    fun `handleTextMessage returns DUPLICATE_MESSAGE_ID when messageId conflicts with another message`() {
        val conflictingMessageId = ChatMessageId(UUID.randomUUID().toString())
        `when`(
            chatMessageService.sendMessage(
                roomId = roomId,
                senderId = userId,
                content = "Conflicting message",
                messageId = conflictingMessageId
            )
        ).thenThrow(DuplicateMessageIdException(conflictingMessageId))

        val incomingPayload = json.encodeToString<WsClientMessage>(
            WsClientMessage.NewMessage(
                roomId = roomId.value,
                content = "Conflicting message",
                messageId = conflictingMessageId.value
            )
        )

        handler.handleMessage(session, TextMessage(incomingPayload))

        val messageCaptor = ArgumentCaptor.forClass(TextMessage::class.java)
        verify(session).sendMessage(messageCaptor.capture())

        val event = json.decodeFromString<WsServerEvent>(messageCaptor.value.payload)
        assertTrue(event is WsServerEvent.Error)
        val errorEvent = event as WsServerEvent.Error
        assertEquals(ChatServerError.DuplicateMessageId, errorEvent.error)
        assertEquals("Message with ID $conflictingMessageId already exists", errorEvent.message)
        assertEquals(roomId.value, errorEvent.roomId)
        assertEquals(conflictingMessageId.value, errorEvent.messageId)
    }

    @Test
    fun `handleTextMessage returns MESSAGE_SEND_FAILED instead of INVALID_JSON when persistence throws unexpected exception`() {
        val clientMessageId = ChatMessageId(UUID.randomUUID().toString())
        `when`(
            chatMessageService.sendMessage(
                roomId = roomId,
                senderId = userId,
                content = "Failing message",
                messageId = clientMessageId
            )
        ).thenThrow(RuntimeException("Database unavailable"))

        val incomingPayload = json.encodeToString<WsClientMessage>(
            WsClientMessage.NewMessage(
                roomId = roomId.value,
                content = "Failing message",
                messageId = clientMessageId.value
            )
        )

        handler.handleMessage(session, TextMessage(incomingPayload))

        val messageCaptor = ArgumentCaptor.forClass(TextMessage::class.java)
        verify(session).sendMessage(messageCaptor.capture())

        val event = json.decodeFromString<WsServerEvent>(messageCaptor.value.payload)
        assertTrue(event is WsServerEvent.Error)
        assertEquals(ChatServerError.MessageSendFailed, (event as WsServerEvent.Error).error)
    }
}
