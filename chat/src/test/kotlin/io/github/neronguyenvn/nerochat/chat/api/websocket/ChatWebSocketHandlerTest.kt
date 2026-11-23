package io.github.neronguyenvn.nerochat.chat.api.websocket

import io.github.neronguyenvn.nerochat.chat.domain.event.InternalChatEvent
import io.github.neronguyenvn.nerochat.chat.domain.exception.DuplicateMessageIdException
import io.github.neronguyenvn.nerochat.chat.domain.model.ChatMessage
import io.github.neronguyenvn.nerochat.chat.domain.model.ChatParticipant
import io.github.neronguyenvn.nerochat.chat.domain.model.ChatRoom
import io.github.neronguyenvn.nerochat.chat.service.ChatMessageService
import io.github.neronguyenvn.nerochat.chat.service.ChatRoomService
import io.github.neronguyenvn.nerochat.contract.ContractJson
import io.github.neronguyenvn.nerochat.contract.chat.ChatRoomType
import io.github.neronguyenvn.nerochat.contract.chat.WsClientMessage
import io.github.neronguyenvn.nerochat.contract.chat.WsServerEvent
import io.github.neronguyenvn.nerochat.contract.chat.error.ChatServerError
import io.github.neronguyenvn.nerochat.domain.type.ChatMessageId
import io.github.neronguyenvn.nerochat.domain.type.ChatRoomId
import io.github.neronguyenvn.nerochat.domain.type.UserId
import io.github.neronguyenvn.nerochat.service.JwtService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.*
import org.springframework.http.HttpHeaders
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.PingMessage
import org.springframework.web.socket.PongMessage
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import java.util.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
class ChatWebSocketHandlerTest {

    private lateinit var chatRoomService: ChatRoomService
    private lateinit var chatMessageService: ChatMessageService
    private lateinit var jwtService: JwtService
    private lateinit var handler: ChatWebSocketHandler
    private lateinit var session: WebSocketSession

    private val json = ContractJson

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
        assertEquals(ChatServerError.InvalidJson.code, (event as WsServerEvent.Error).error)
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
        assertEquals(ChatServerError.DuplicateMessageId.code, errorEvent.error)
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
        assertEquals(ChatServerError.MessageSendFailed.code, (event as WsServerEvent.Error).error)
    }

    @Test
    fun `onRoomCreated broadcasts NewMessage for initial message when lastMessage is non-null`() {
        val newRoomId = ChatRoomId(UUID.randomUUID())
        val targetUserId = UserId(UUID.randomUUID())
        val targetParticipant = ChatParticipant(
            userId = targetUserId,
            email = "target@example.com",
            displayName = "Target",
            profilePictureUrl = null,
        )
        val now = Clock.System.now()
        val initialMessage = ChatMessage(
            id = ChatMessageId(UUID.randomUUID().toString()),
            roomId = newRoomId,
            sender = participant,
            content = "First direct message",
            createdAt = now,
        )
        val directRoom = ChatRoom(
            id = newRoomId,
            type = ChatRoomType.DIRECT,
            name = null,
            creator = participant,
            participants = setOf(participant, targetParticipant),
            lastMessage = initialMessage,
            lastActivityAt = now,
            createdAt = now,
        )

        handler.onRoomCreated(InternalChatEvent.ChatRoomCreatedEvent(chatRoom = directRoom))

        val messageCaptor = ArgumentCaptor.forClass(TextMessage::class.java)
        verify(session).sendMessage(messageCaptor.capture())

        val event = json.decodeFromString<WsServerEvent>(messageCaptor.value.payload)
        assertTrue(event is WsServerEvent.NewMessage)
        val newMessageEvent = event as WsServerEvent.NewMessage
        assertEquals(initialMessage.id.value, newMessageEvent.message.id)
        assertEquals(newRoomId.value, newMessageEvent.message.roomId)
        assertEquals("First direct message", newMessageEvent.message.content)
        assertEquals(userId.value, newMessageEvent.message.senderId)
    }

    @Test
    fun `onRoomCreated registers room sessions without broadcasting NewMessage when lastMessage is null`() {
        val groupRoomId = ChatRoomId(UUID.randomUUID())
        val now = Clock.System.now()
        val groupRoom = ChatRoom(
            id = groupRoomId,
            type = ChatRoomType.GROUP,
            name = "Group Room",
            creator = participant,
            participants = setOf(participant),
            lastMessage = null,
            lastActivityAt = now,
            createdAt = now,
        )

        handler.onRoomCreated(InternalChatEvent.ChatRoomCreatedEvent(chatRoom = groupRoom))

        verify(session, never()).sendMessage(any())
    }

    @Test
    fun `onJoinChatRoom broadcasts ParticipantJoined with non-null addedBy`() {
        val newUserId = UserId(UUID.randomUUID())
        val newUser = ChatParticipant(
            userId = newUserId,
            email = "newuser@example.com",
            displayName = "New User",
            profilePictureUrl = null,
        )

        handler.onJoinChatRoom(
            InternalChatEvent.ChatParticipantJoinedEvent(
                roomId = roomId,
                newUsers = setOf(newUser),
                addedBy = participant,
            )
        )

        val messageCaptor = ArgumentCaptor.forClass(TextMessage::class.java)
        verify(session).sendMessage(messageCaptor.capture())

        val event = json.decodeFromString<WsServerEvent>(messageCaptor.value.payload)
        assertTrue(event is WsServerEvent.ParticipantJoined)
        val joinedEvent = event as WsServerEvent.ParticipantJoined
        assertEquals(roomId.value, joinedEvent.roomId)
        assertEquals(1, joinedEvent.newUsers.size)
        assertEquals(newUserId.value, joinedEvent.newUsers.first().userId)
        assertEquals(userId.value, joinedEvent.addedBy?.userId)
        assertEquals("user@example.com", joinedEvent.addedBy?.email)
        assertEquals("User", joinedEvent.addedBy?.displayName)
    }

    @Test
    fun `onRoomCreated broadcasts ParticipantJoined to online members for newly created GROUP room`() {
        val groupRoomId = ChatRoomId(UUID.randomUUID())
        val otherUserId = UserId(UUID.randomUUID())
        val otherParticipant = ChatParticipant(
            userId = otherUserId,
            email = "other@example.com",
            displayName = "Other",
            profilePictureUrl = null,
        )
        val now = Clock.System.now()
        val groupRoom = ChatRoom(
            id = groupRoomId,
            type = ChatRoomType.GROUP,
            name = "Team Chat",
            creator = participant,
            participants = setOf(participant, otherParticipant),
            lastMessage = null,
            lastActivityAt = now,
            createdAt = now,
        )

        handler.onRoomCreated(InternalChatEvent.ChatRoomCreatedEvent(chatRoom = groupRoom))

        val messageCaptor = ArgumentCaptor.forClass(TextMessage::class.java)
        verify(session).sendMessage(messageCaptor.capture())

        val event = json.decodeFromString<WsServerEvent>(messageCaptor.value.payload)
        assertTrue(event is WsServerEvent.ParticipantJoined)
        val joined = event as WsServerEvent.ParticipantJoined
        assertEquals(groupRoomId.value, joined.roomId)
        assertEquals(listOf(otherUserId.value), joined.newUsers.map { it.userId })
        assertEquals(userId.value, joined.addedBy?.userId)
    }

    @Test
    fun `offline room creation and join do not poison room cache when user later connects`() {
        val offlineUserId = UserId(UUID.randomUUID())
        val offlineParticipant = ChatParticipant(
            userId = offlineUserId,
            email = "offline@example.com",
            displayName = "Offline",
            profilePictureUrl = null,
        )
        val existingRoomId = ChatRoomId(UUID.randomUUID())
        val newRoomId = ChatRoomId(UUID.randomUUID())
        val joinedRoomId = ChatRoomId(UUID.randomUUID())
        val now = Clock.System.now()

        val existingRoom = ChatRoom(
            id = existingRoomId,
            type = ChatRoomType.DIRECT,
            name = null,
            creator = offlineParticipant,
            participants = setOf(offlineParticipant, participant),
            lastMessage = null,
            lastActivityAt = now,
            createdAt = now,
        )
        val newRoom = ChatRoom(
            id = newRoomId,
            type = ChatRoomType.GROUP,
            name = "New Group",
            creator = participant,
            participants = setOf(participant, offlineParticipant),
            lastMessage = null,
            lastActivityAt = now,
            createdAt = now,
        )
        val joinedRoom = ChatRoom(
            id = joinedRoomId,
            type = ChatRoomType.GROUP,
            name = "Joined Group",
            creator = participant,
            participants = setOf(participant, offlineParticipant),
            lastMessage = null,
            lastActivityAt = now,
            createdAt = now,
        )

        handler.onRoomCreated(InternalChatEvent.ChatRoomCreatedEvent(chatRoom = newRoom))
        handler.onJoinChatRoom(
            InternalChatEvent.ChatParticipantJoinedEvent(
                roomId = joinedRoomId,
                newUsers = setOf(offlineParticipant),
                addedBy = participant,
            )
        )

        val offlineToken = "Bearer offline-token"
        val offlineSession = mock(WebSocketSession::class.java)
        val headers = HttpHeaders().apply { add(HttpHeaders.AUTHORIZATION, offlineToken) }
        `when`(offlineSession.id).thenReturn("offline-session-1")
        `when`(offlineSession.handshakeHeaders).thenReturn(headers)
        `when`(offlineSession.isOpen).thenReturn(true)
        `when`(jwtService.validateAccessToken(offlineToken)).thenReturn(true)
        `when`(jwtService.getUserIdFromToken(offlineToken)).thenReturn(offlineUserId)
        `when`(chatRoomService.findChatRoomsByUser(offlineUserId)).thenReturn(
            listOf(existingRoom, newRoom, joinedRoom)
        )

        handler.afterConnectionEstablished(offlineSession)
        verify(chatRoomService).findChatRoomsByUser(offlineUserId)

        val messageId = ChatMessageId(UUID.randomUUID().toString())
        val savedMessage = ChatMessage(
            id = messageId,
            roomId = existingRoomId,
            sender = offlineParticipant,
            content = "Back online",
            createdAt = Clock.System.now(),
        )
        `when`(
            chatMessageService.sendMessage(
                roomId = existingRoomId,
                senderId = offlineUserId,
                content = "Back online",
                messageId = messageId,
            )
        ).thenReturn(savedMessage)

        val payload = json.encodeToString<WsClientMessage>(
            WsClientMessage.NewMessage(
                roomId = existingRoomId.value,
                content = "Back online",
                messageId = messageId.value,
            )
        )
        handler.handleMessage(offlineSession, TextMessage(payload))

        val captor = ArgumentCaptor.forClass(TextMessage::class.java)
        verify(offlineSession).sendMessage(captor.capture())
        val event = json.decodeFromString<WsServerEvent>(captor.value.payload)
        assertTrue(event is WsServerEvent.NewMessage)
        assertEquals(existingRoomId.value, (event as WsServerEvent.NewMessage).message.roomId)
    }

    @Test
    fun `heartbeat allows scheduling jitter grace margin and closes only after PONG_TIMEOUT_MS`() {
        val fakeNow = AtomicLong(1_000L)
        val timedHandler = ChatWebSocketHandler(
            chatRoomService = chatRoomService,
            chatMessageService = chatMessageService,
            jwtService = jwtService,
            json = json,
            currentTimeMillis = { fakeNow.get() },
        )
        timedHandler.afterConnectionEstablished(session)

        // Simulate jitter past PING_INTERVAL_MS (e.g. 31s) but within PONG_TIMEOUT_MS (60s)
        fakeNow.addAndGet(ChatWebSocketHandler.PING_INTERVAL_MS + 1_000L)
        timedHandler.pingClients()

        verify(session).sendMessage(any(PingMessage::class.java))
        verify(session, never()).close(any())

        // Pong arrives at 32s, resetting timeout window
        fakeNow.addAndGet(1_000L)
        timedHandler.handleMessage(session, PongMessage())

        // Advance another 59s (within 60s of pong) -> still healthy
        fakeNow.addAndGet(ChatWebSocketHandler.PONG_TIMEOUT_MS - 1_000L)
        timedHandler.pingClients()
        verify(session, times(2)).sendMessage(any(PingMessage::class.java))
        verify(session, never()).close(any())

        // Advance past PONG_TIMEOUT_MS without another pong -> closed as SESSION_NOT_RELIABLE
        fakeNow.addAndGet(2_000L)
        timedHandler.pingClients()
        verify(session).close(CloseStatus.SESSION_NOT_RELIABLE.withReason("Ping timeout"))
    }

    @Test
    fun `pingClients evicts stale closed sessions from all maps`() {
        `when`(session.isOpen).thenReturn(false)

        handler.pingClients()

        verify(session, never()).sendMessage(any(PingMessage::class.java))
        verify(session, never()).close(any())

        // Re-open mock flag shouldn't receive broadcasts because session was evicted
        `when`(session.isOpen).thenReturn(true)
        handler.onDeleteMessage(
            InternalChatEvent.MessageDeletedEvent(
                roomId = roomId,
                messageId = ChatMessageId(UUID.randomUUID().toString()),
            )
        )
        verify(session, never()).sendMessage(any())
    }

    @Test
    fun `concurrent connect and disconnect for same user preserves active session room subscriptions`() {
        val executor = Executors.newFixedThreadPool(2)
        try {
            repeat(50) { iteration ->
                val s1 = mock(WebSocketSession::class.java)
                val s2 = mock(WebSocketSession::class.java)
                val headers = HttpHeaders().apply { add(HttpHeaders.AUTHORIZATION, token) }

                `when`(s1.id).thenReturn("conc-s1-$iteration")
                `when`(s1.handshakeHeaders).thenReturn(headers)
                `when`(s1.isOpen).thenReturn(true)

                `when`(s2.id).thenReturn("conc-s2-$iteration")
                `when`(s2.handshakeHeaders).thenReturn(headers)
                `when`(s2.isOpen).thenReturn(true)

                handler.afterConnectionEstablished(s1)

                val ready = CountDownLatch(2)
                val start = CountDownLatch(1)

                val closeFuture = executor.submit {
                    ready.countDown()
                    start.await(5, TimeUnit.SECONDS)
                    handler.afterConnectionClosed(s1, CloseStatus.NORMAL)
                }
                val openFuture = executor.submit {
                    ready.countDown()
                    start.await(5, TimeUnit.SECONDS)
                    handler.afterConnectionEstablished(s2)
                }

                assertTrue(ready.await(5, TimeUnit.SECONDS))
                start.countDown()
                closeFuture.get(5, TimeUnit.SECONDS)
                openFuture.get(5, TimeUnit.SECONDS)

                val msgId = ChatMessageId(UUID.randomUUID().toString())
                val savedMessage = ChatMessage(
                    id = msgId,
                    roomId = roomId,
                    sender = participant,
                    content = "Concurrent-$iteration",
                    createdAt = Clock.System.now(),
                )
                `when`(
                    chatMessageService.sendMessage(
                        roomId = roomId,
                        senderId = userId,
                        content = "Concurrent-$iteration",
                        messageId = msgId,
                    )
                ).thenReturn(savedMessage)

                val payload = json.encodeToString<WsClientMessage>(
                    WsClientMessage.NewMessage(
                        roomId = roomId.value,
                        content = "Concurrent-$iteration",
                        messageId = msgId.value,
                    )
                )
                handler.handleMessage(s2, TextMessage(payload))

                verify(s2).sendMessage(any(TextMessage::class.java))
                handler.afterConnectionClosed(s2, CloseStatus.NORMAL)
            }
        } finally {
            executor.shutdownNow()
        }
    }
}
