package io.github.neronguyenvn.nerochat.chat.api.websocket

import io.github.neronguyenvn.nerochat.chat.api.mapper.asDto
import io.github.neronguyenvn.nerochat.chat.domain.event.InternalChatEvent
import io.github.neronguyenvn.nerochat.chat.domain.exception.DuplicateMessageIdException
import io.github.neronguyenvn.nerochat.chat.service.ChatMessageService
import io.github.neronguyenvn.nerochat.chat.service.ChatRoomService
import io.github.neronguyenvn.nerochat.contract.chat.WsClientMessage
import io.github.neronguyenvn.nerochat.contract.chat.WsServerEvent
import io.github.neronguyenvn.nerochat.contract.chat.error.ChatServerError
import io.github.neronguyenvn.nerochat.domain.type.ChatMessageId
import io.github.neronguyenvn.nerochat.domain.type.ChatRoomId
import io.github.neronguyenvn.nerochat.domain.type.UserId
import io.github.neronguyenvn.nerochat.service.JwtService
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.PingMessage
import org.springframework.web.socket.PongMessage
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.handler.TextWebSocketHandler
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

@Component
class ChatWebSocketHandler(
    private val chatRoomService: ChatRoomService,
    private val chatMessageService: ChatMessageService,
    private val jwtService: JwtService,
    private val json: Json = defaultJson
) : TextWebSocketHandler() {

    private data class UserSession(
        val userId: UserId,
        val session: WebSocketSession,
        var lastPongTimestamp: AtomicLong = AtomicLong(System.currentTimeMillis())
    )

    private val logger = LoggerFactory.getLogger(javaClass)

    private val sessionsById = ConcurrentHashMap<String, UserSession>()
    private val sessionIdsByUserId = ConcurrentHashMap<UserId, MutableSet<String>>()
    private val roomIdsByUserId = ConcurrentHashMap<UserId, MutableSet<ChatRoomId>>()
    private val sessionIdsByRoomId = ConcurrentHashMap<ChatRoomId, MutableSet<String>>()


    override fun afterConnectionEstablished(session: WebSocketSession) {
        val authHeader = session
            .handshakeHeaders
            .getFirst(HttpHeaders.AUTHORIZATION)

        if (authHeader == null) {
            logger.warn("Session ${session.id} was closed due to missing Authorization header")
            session.close(CloseStatus.POLICY_VIOLATION.withReason("Authentication failed"))
            return
        }

        if (!jwtService.validateAccessToken(token = authHeader)) {
            session.close(CloseStatus.POLICY_VIOLATION.withReason("Authentication failed"))
            return
        }

        val userId = jwtService.getUserIdFromToken(token = authHeader)

        // TODO: Wrap WebSocketSession with ConcurrentWebSocketSessionDecorator in afterConnectionEstablished
        // to prevent IllegalStateException from concurrent writes across ping scheduler, broadcast threads, and event listeners.
        val userSession = UserSession(
            userId = userId,
            session = session
        )

        sessionsById[session.id] = userSession

        sessionIdsByUserId
            .computeIfAbsent(userId) { ConcurrentHashMap.newKeySet() }
            .add(session.id)

        val roomIds = roomIdsByUserId.computeIfAbsent(userId) {
            val userChatRooms = chatRoomService.findChatRoomsByUser(userId = userId).map { it.id }
            ConcurrentHashMap.newKeySet<ChatRoomId>().apply { addAll(userChatRooms) }
        }

        roomIds.forEach { roomId ->
            sessionIdsByRoomId
                .computeIfAbsent(roomId) { ConcurrentHashMap.newKeySet() }
                .add(session.id)
        }

        logger.info("Websocket connection established for user $userId")
    }

    override fun afterConnectionClosed(session: WebSocketSession, status: CloseStatus) {
        val userSession = sessionsById.remove(session.id) ?: return
        val userId = userSession.userId

        roomIdsByUserId[userId]?.forEach { roomId ->
            sessionIdsByRoomId[roomId]?.remove(session.id)
        }

        sessionIdsByUserId[userId]?.let { sessions ->
            sessions.remove(session.id)
            if (sessions.isEmpty()) {
                sessionIdsByUserId.remove(userId)
                roomIdsByUserId.remove(userId)
            }
        }

        logger.info("WebSocket closed: user={}, session={}, status={}", userId, session.id, status)
    }

    override fun handleTransportError(session: WebSocketSession, exception: Throwable) {
        logger.error("Transport error on session {}", session.id, exception)
        if (!session.isOpen) return

        runCatching {
            session.close(CloseStatus.SERVER_ERROR.withReason("Transport error"))
        }
    }

    @Scheduled(fixedDelay = PING_INTERVAL_MS)
    private fun pingClients() {
        val currentTime = System.currentTimeMillis()
        val sessionsToClose = mutableListOf<String>()

        sessionsById.entries.forEach { (sessionId, userSession) ->
            try {
                if (userSession.session.isOpen) {
                    val lastPong = userSession.lastPongTimestamp.get()
                    if (currentTime - lastPong > PONG_TIMEOUT_MS) {
                        logger.warn("Session $sessionId has timed out, closing connection.")
                        sessionsToClose.add(sessionId)
                        return@forEach
                    }

                    userSession.session.sendMessage(PingMessage())
                    logger.debug("Sent ping to {}", userSession.userId)
                }
            } catch (e: Exception) {
                logger.error("Could not ping session $sessionId", e)
                sessionsToClose.add(sessionId)
            }
        }

        sessionsToClose.forEach { sessionId ->
            sessionsById[sessionId]?.session?.let { session ->
                try {
                    session.close(CloseStatus.SESSION_NOT_RELIABLE.withReason("Ping timeout"))
                } catch (e: Exception) {
                    logger.error("Couldn't close sessions for session ${session.id}", e)
                }
            }
        }
    }

    override fun handlePongMessage(session: WebSocketSession, message: PongMessage) {
        sessionsById[session.id]?.lastPongTimestamp?.set(System.currentTimeMillis())
        logger.debug("Received pong from ${session.id}")
    }

    override fun handleTextMessage(session: WebSocketSession, message: TextMessage) {
        logger.debug("Received message ${message.payload}")
        val userSession = sessionsById[session.id] ?: return

        val incoming = try {
            json.decodeFromString<WsClientMessage>(message.payload)
        } catch (e: SerializationException) {
            logger.warn("Payload decode error from session ${session.id}: ${e.message}")
            userSession.sendError(
                code = ChatServerError.InvalidJson.code,
                message = "Malformed payload"
            )
            return
        } catch (e: IllegalArgumentException) {
            logger.warn("Payload decode error from session ${session.id}: ${e.message}")
            userSession.sendError(
                code = ChatServerError.InvalidJson.code,
                message = "Malformed payload"
            )
            return
        }

        try {
            when (incoming) {
                is WsClientMessage.NewMessage -> {
                    handleIncomingNewMessage(
                        userSession = userSession,
                        incoming = incoming
                    )
                }
            }
        } catch (e: IllegalArgumentException) {
            logger.warn("Invalid message parameters from session ${session.id}: ${e.message}")
            userSession.sendError(
                code = ChatServerError.InvalidPayload.code,
                message = "Invalid message parameters"
            )
        } catch (e: DuplicateMessageIdException) {
            logger.warn("Duplicate message ID from session ${session.id}: ${e.message}")
            userSession.sendError(
                code = ChatServerError.DuplicateMessageId.code,
                message = e.message ?: "Duplicate message ID",
                roomId = (incoming as? WsClientMessage.NewMessage)?.roomId,
                messageId = (incoming as? WsClientMessage.NewMessage)?.messageId
            )
        } catch (e: Exception) {
            logger.error("Failed to handle WebSocket message from session ${session.id}", e)
            userSession.sendError(
                code = ChatServerError.MessageSendFailed.code,
                message = "Failed to send message",
                roomId = (incoming as? WsClientMessage.NewMessage)?.roomId,
                messageId = (incoming as? WsClientMessage.NewMessage)?.messageId
            )
        }
    }

    private fun UserSession.sendError(
        code: String,
        message: String,
        roomId: String? = null,
        messageId: String? = null,
    ) {
        val event: WsServerEvent = WsServerEvent.Error(
            code = code,
            message = message,
            roomId = roomId,
            messageId = messageId,
        )
        val payload = TextMessage(json.encodeToString(event))
        sendMessage(payload)
    }

    private fun handleIncomingNewMessage(
        userSession: UserSession,
        incoming: WsClientMessage.NewMessage,
    ) {
        val senderId = userSession.userId
        val roomId = ChatRoomId(incoming.roomId)
        val allowedChatRooms = roomIdsByUserId[senderId]
        if (allowedChatRooms == null || roomId !in allowedChatRooms) {
            userSession.sendError(
                code = ChatServerError.ChatRoomNotFound.code,
                message = "Chat room not found or access denied",
                roomId = incoming.roomId,
                messageId = incoming.messageId
            )
            return
        }

        val savedMessage = chatMessageService.sendMessage(
            roomId = roomId,
            senderId = senderId,
            content = incoming.content,
            messageId = incoming.messageId?.let { ChatMessageId(it) }
        )

        broadcastToChatRoom(
            roomId = roomId,
            event = WsServerEvent.NewMessage(savedMessage.asDto())
        )
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    private fun onDeleteMessage(event: InternalChatEvent.MessageDeletedEvent) {
        broadcastToChatRoom(
            roomId = event.roomId,
            event = WsServerEvent.MessageDeleted(
                roomId = event.roomId.value,
                messageId = event.messageId.value
            )
        )
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    private fun onJoinChatRoom(event: InternalChatEvent.ChatParticipantJoinedEvent) {
        event.newUsers.forEach { user ->
            roomIdsByUserId
                .computeIfAbsent(user.userId) { ConcurrentHashMap.newKeySet() }
                .add(event.roomId)

            sessionIdsByUserId[user.userId]?.let { sessions ->
                sessionIdsByRoomId
                    .computeIfAbsent(event.roomId) { ConcurrentHashMap.newKeySet() }
                    .addAll(sessions)
            }
        }

        broadcastToChatRoom(
            roomId = event.roomId,
            event = WsServerEvent.ParticipantJoined(
                roomId = event.roomId.value,
                newUsers = event.newUsers.map { it.asDto() },
            )
        )
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    private fun onRoomCreated(event: InternalChatEvent.ChatRoomCreatedEvent) {
        event.chatRoom.participants.forEach { user ->
            roomIdsByUserId
                .computeIfAbsent(user.userId) { ConcurrentHashMap.newKeySet() }
                .add(event.chatRoom.id)

            sessionIdsByUserId[user.userId]?.let { sessions ->
                sessionIdsByRoomId
                    .computeIfAbsent(event.chatRoom.id) { ConcurrentHashMap.newKeySet() }
                    .addAll(sessions)
            }
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    private fun onLeftChatRoom(event: InternalChatEvent.ChatParticipantLeftEvent) {
        broadcastToChatRoom(
            roomId = event.roomId,
            event = WsServerEvent.ParticipantLeft(
                roomId = event.roomId.value,
                leftUser = event.leftUser.asDto(),
            )
        )

        val leftUserId = event.leftUser.userId
        roomIdsByUserId[leftUserId]?.remove(event.roomId)
        sessionIdsByUserId[leftUserId]?.forEach { sessionId ->
            sessionIdsByRoomId[event.roomId]?.remove(sessionId)
        }
    }

    private fun broadcastToChatRoom(
        roomId: ChatRoomId,
        event: WsServerEvent
    ) {
        val sessionIds = sessionIdsByRoomId[roomId] ?: return
        val payload = TextMessage(json.encodeToString(event))

        sessionIds.forEach { sessionId ->
            val session = sessionsById[sessionId] ?: return@forEach
            val chatRooms = roomIdsByUserId[session.userId]
            if (chatRooms == null || roomId !in chatRooms) {
                sessionIds.remove(sessionId)
                return@forEach
            }

            session.sendMessage(message = payload)
        }
    }

    private fun UserSession.sendMessage(message: TextMessage) {
        if (!session.isOpen) return

        try {
            session.sendMessage(message)
            logger.debug("Sent message to user {}: {}", userId, message)
        } catch (e: Exception) {
            logger.error("Error while sending message to $userId", e)
        }
    }

    companion object {
        private const val PING_INTERVAL_MS = 30_000L
        private const val PONG_TIMEOUT_MS = 30_000L

        private const val EVENT_TYPE = "type"
        private val defaultJson = Json {
            classDiscriminator = EVENT_TYPE
        }
    }
}
