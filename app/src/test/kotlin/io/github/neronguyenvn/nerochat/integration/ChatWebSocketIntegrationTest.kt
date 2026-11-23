package io.github.neronguyenvn.nerochat.integration

import io.github.neronguyenvn.nerochat.chat.infra.database.model.ChatParticipantEntity
import io.github.neronguyenvn.nerochat.chat.infra.database.repository.ChatParticipantRepository
import io.github.neronguyenvn.nerochat.chat.service.ChatRoomService
import io.github.neronguyenvn.nerochat.contract.ContractJson
import io.github.neronguyenvn.nerochat.contract.chat.WsClientMessage
import io.github.neronguyenvn.nerochat.contract.chat.WsServerEvent
import io.github.neronguyenvn.nerochat.domain.type.UserId
import io.github.neronguyenvn.nerochat.service.JwtService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketHttpHeaders
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.client.standard.StandardWebSocketClient
import org.springframework.web.socket.handler.TextWebSocketHandler
import java.net.URI
import java.util.UUID
import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class ChatWebSocketIntegrationTest : AbstractContainerIntegrationTest() {

    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var chatParticipantRepository: ChatParticipantRepository

    @Autowired
    private lateinit var chatRoomService: ChatRoomService

    @Autowired
    private lateinit var jwtService: JwtService

    private val json = ContractJson

    @Test
    fun `offline room creation does not poison cache on connect and online member receives group room creation event`() {
        val userAId = UUID.randomUUID()
        val userBId = UUID.randomUUID()
        val userCId = UUID.randomUUID()

        chatParticipantRepository.saveAndFlush(
            ChatParticipantEntity(
                userId = userAId,
                email = "ws-a-$userAId@example.com",
                displayName = "User A",
            )
        )
        chatParticipantRepository.saveAndFlush(
            ChatParticipantEntity(
                userId = userBId,
                email = "ws-b-$userBId@example.com",
                displayName = "User B",
            )
        )
        chatParticipantRepository.saveAndFlush(
            ChatParticipantEntity(
                userId = userCId,
                email = "ws-c-$userCId@example.com",
                displayName = "User C",
            )
        )

        // Create both a direct room and a group room while User B is offline
        val directRoom = chatRoomService.createDirectChatRoom(
            creatorId = UserId(userAId),
            targetUserId = UserId(userBId),
            message = "Initial offline DM",
        )
        val offlineGroupRoom = chatRoomService.createGroupChatRoom(
            creatorId = UserId(userAId),
            name = "Offline Group",
            participantIds = listOf(UserId(userBId), UserId(userCId)),
        )

        // Connect User B over live Tomcat WebSocket
        val receivedEvents: BlockingQueue<WsServerEvent> = LinkedBlockingQueue()
        val client = StandardWebSocketClient()
        val headers = WebSocketHttpHeaders().apply {
            add(WebSocketHttpHeaders.AUTHORIZATION, "Bearer ${jwtService.generateAccessToken(UserId(userBId))}")
        }
        val session = client.execute(
            object : TextWebSocketHandler() {
                override fun handleTextMessage(session: WebSocketSession, message: TextMessage) {
                    receivedEvents.offer(json.decodeFromString<WsServerEvent>(message.payload))
                }
            },
            headers,
            URI.create("ws://localhost:$port/ws/chat"),
        ).get(10, TimeUnit.SECONDS)

        try {
            // Verify User B can send a message in both pre-existing rooms over the live WebSocket
            val directMsgId = UUID.randomUUID().toString()
            session.sendMessage(
                TextMessage(
                    json.encodeToString<WsClientMessage>(
                        WsClientMessage.NewMessage(
                            roomId = directRoom.id.value,
                            content = "Reply in direct room",
                            messageId = directMsgId,
                        )
                    )
                )
            )

            val directEcho = receivedEvents.poll(10, TimeUnit.SECONDS)
            assertNotNull(directEcho)
            assertTrue(directEcho is WsServerEvent.NewMessage)
            assertEquals(directMsgId, (directEcho as WsServerEvent.NewMessage).message.id)

            val groupMsgId = UUID.randomUUID().toString()
            session.sendMessage(
                TextMessage(
                    json.encodeToString<WsClientMessage>(
                        WsClientMessage.NewMessage(
                            roomId = offlineGroupRoom.id.value,
                            content = "Reply in offline group",
                            messageId = groupMsgId,
                        )
                    )
                )
            )

            val groupEcho = receivedEvents.poll(10, TimeUnit.SECONDS)
            assertNotNull(groupEcho)
            assertTrue(groupEcho is WsServerEvent.NewMessage)
            assertEquals(groupMsgId, (groupEcho as WsServerEvent.NewMessage).message.id)

            // Now create a new GROUP room while User B is online -> User B must receive ParticipantJoined
            val liveGroupRoom = chatRoomService.createGroupChatRoom(
                creatorId = UserId(userAId),
                name = "Live Group",
                participantIds = listOf(UserId(userBId), UserId(userCId)),
            )

            val joinedEvent = receivedEvents.poll(10, TimeUnit.SECONDS)
            assertNotNull(joinedEvent)
            assertTrue(joinedEvent is WsServerEvent.ParticipantJoined)
            val participantJoined = joinedEvent as WsServerEvent.ParticipantJoined
            assertEquals(liveGroupRoom.id.value, participantJoined.roomId)
            assertEquals(userAId.toString(), participantJoined.addedBy?.userId)
        } finally {
            session.close()
        }
    }
}
