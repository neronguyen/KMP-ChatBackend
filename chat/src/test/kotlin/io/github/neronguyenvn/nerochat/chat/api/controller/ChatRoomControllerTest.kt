package io.github.neronguyenvn.nerochat.chat.api.controller

import io.github.neronguyenvn.nerochat.api.validation.InvalidUuidException
import io.github.neronguyenvn.nerochat.chat.domain.exception.ChatRoomNotFoundException
import io.github.neronguyenvn.nerochat.chat.domain.exception.ParticipantNotInRoomException
import io.github.neronguyenvn.nerochat.chat.domain.model.ChatMessage
import io.github.neronguyenvn.nerochat.chat.domain.model.ChatParticipant
import io.github.neronguyenvn.nerochat.chat.domain.model.ChatRoom
import io.github.neronguyenvn.nerochat.chat.service.ChatMessageService
import io.github.neronguyenvn.nerochat.chat.service.ChatRoomService
import io.github.neronguyenvn.nerochat.contract.chat.*
import io.github.neronguyenvn.nerochat.domain.type.ChatMessageId
import io.github.neronguyenvn.nerochat.domain.type.ChatRoomId
import io.github.neronguyenvn.nerochat.domain.type.UserId
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito.*
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import java.util.*
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
class ChatRoomControllerTest {

    private lateinit var chatRoomService: ChatRoomService
    private lateinit var chatMessageService: ChatMessageService
    private lateinit var controller: ChatRoomController

    private val userId = UserId(UUID.randomUUID())
    private val chatRoomId = ChatRoomId(UUID.randomUUID())

    @BeforeEach
    fun setUp() {
        chatRoomService = mock(ChatRoomService::class.java)
        chatMessageService = mock(ChatMessageService::class.java)
        controller = ChatRoomController(chatRoomService, chatMessageService)

        val authentication = UsernamePasswordAuthenticationToken(userId, null, emptyList())
        SecurityContextHolder.getContext().authentication = authentication
    }

    @AfterEach
    fun tearDown() {
        SecurityContextHolder.clearContext()
    }

    @Test
    fun `getChatMessages returns messages when requester is participant`() {
        val chatRoom = mock(ChatRoom::class.java)
        `when`(chatRoomService.existsById(chatRoomId)).thenReturn(true)
        `when`(chatRoomService.getChatRoomById(chatRoomId, userId)).thenReturn(chatRoom)

        val expectedResponse = ChatMessageSliceDto(
            messages = listOf(
                ChatMessageDto(
                    id = UUID.randomUUID().toString(),
                    roomId = chatRoomId.value,
                    senderId = userId.value,
                    content = "Hello",
                    createdAt = Clock.System.now()
                )
            ),
            hasMore = false
        )
        `when`(chatMessageService.getChatMessages(chatRoomId, null)).thenReturn(expectedResponse)

        val result = controller.getChatMessages(roomId = chatRoomId)

        assertEquals(expectedResponse, result)
        verify(chatRoomService).existsById(chatRoomId)
        verify(chatRoomService).getChatRoomById(chatRoomId, userId)
        verify(chatMessageService).getChatMessages(chatRoomId, null)
    }

    @Test
    fun `getChatMessages throws ChatRoomNotFoundException when room does not exist`() {
        `when`(chatRoomService.existsById(chatRoomId)).thenReturn(false)

        assertThrows<ChatRoomNotFoundException> {
            controller.getChatMessages(roomId = chatRoomId)
        }

        verify(chatRoomService).existsById(chatRoomId)
        verifyNoMoreInteractions(chatRoomService, chatMessageService)
    }

    @Test
    fun `getChatMessages throws ParticipantNotInRoomException when requester is not participant`() {
        `when`(chatRoomService.existsById(chatRoomId)).thenReturn(true)
        `when`(chatRoomService.getChatRoomById(chatRoomId, userId)).thenReturn(null)

        assertThrows<ParticipantNotInRoomException> {
            controller.getChatMessages(roomId = chatRoomId)
        }

        verify(chatRoomService).existsById(chatRoomId)
        verify(chatRoomService).getChatRoomById(chatRoomId, userId)
        verifyNoMoreInteractions(chatRoomService, chatMessageService)
    }

    @Test
    fun `createDirectChatRoom calls chatRoomService and returns unified ChatRoomDto`() {
        val targetUserId = UserId(UUID.randomUUID())
        val roomId = ChatRoomId(UUID.randomUUID())
        val creatorParticipant = ChatParticipant(
            userId = userId,
            email = "creator@example.com",
            displayName = "Creator",
            profilePictureUrl = null
        )
        val targetParticipant = ChatParticipant(
            userId = targetUserId,
            email = "target@example.com",
            displayName = "Target",
            profilePictureUrl = null
        )
        val now = Clock.System.now()
        val message = ChatMessage(
            id = ChatMessageId(UUID.randomUUID().toString()),
            roomId = roomId,
            sender = creatorParticipant,
            content = "Hi there",
            createdAt = now
        )
        val chatRoom = ChatRoom(
            id = roomId,
            type = ChatRoomType.DIRECT,
            name = null,
            creator = creatorParticipant,
            participants = setOf(creatorParticipant, targetParticipant),
            lastMessage = message,
            lastActivityAt = now,
            createdAt = now
        )

        `when`(chatRoomService.createDirectChatRoom(userId, targetUserId, "Hi there"))
            .thenReturn(chatRoom)

        val request = CreateDirectChatRoomRequest(
            targetUserId = targetUserId.value,
            message = "Hi there"
        )

        val result = controller.createDirectChatRoom(request)

        assertEquals(roomId.value, result.id)
        assertEquals(ChatRoomType.DIRECT, result.type)
        assertNull(result.name)
        assertEquals(userId.value, result.creator.userId)
        assertEquals(2, result.participants.size)
        assertNotNull(result.lastMessage)
        assertEquals("Hi there", result.lastMessage?.content)

        verify(chatRoomService).createDirectChatRoom(userId, targetUserId, "Hi there")
    }

    @Test
    fun `createGroupChatRoom calls chatRoomService and returns unified ChatRoomDto`() {
        val member1 = UserId(UUID.randomUUID())
        val member2 = UserId(UUID.randomUUID())
        val roomId = ChatRoomId(UUID.randomUUID())
        val creatorParticipant = ChatParticipant(
            userId = userId,
            email = "creator@example.com",
            displayName = "Creator",
            profilePictureUrl = null
        )
        val member1Participant = ChatParticipant(
            userId = member1,
            email = "member1@example.com",
            displayName = "Member 1",
            profilePictureUrl = null
        )
        val member2Participant = ChatParticipant(
            userId = member2,
            email = "member2@example.com",
            displayName = "Member 2",
            profilePictureUrl = null
        )
        val now = Clock.System.now()
        val chatRoom = ChatRoom(
            id = roomId,
            type = ChatRoomType.GROUP,
            name = "Awesome Group",
            creator = creatorParticipant,
            participants = setOf(creatorParticipant, member1Participant, member2Participant),
            lastMessage = null,
            lastActivityAt = now,
            createdAt = now
        )

        `when`(chatRoomService.createGroupChatRoom(userId, "Awesome Group", listOf(member1, member2)))
            .thenReturn(chatRoom)

        val request = CreateGroupChatRoomRequest(
            name = "Awesome Group",
            participantIds = listOf(member1.value, member2.value)
        )

        val result = controller.createGroupChatRoom(request)

        assertEquals(roomId.value, result.id)
        assertEquals(ChatRoomType.GROUP, result.type)
        assertEquals("Awesome Group", result.name)
        assertEquals(userId.value, result.creator.userId)
        assertEquals(3, result.participants.size)
        assertNull(result.lastMessage)

        verify(chatRoomService).createGroupChatRoom(userId, "Awesome Group", listOf(member1, member2))
    }

    @Test
    fun `getChatRoom throws InvalidUuidException when roomId is invalid`() {
        val invalidRoomId = ChatRoomId("not-a-valid-uuid")

        val ex = assertThrows<InvalidUuidException> {
            controller.getChatRoom(roomId = invalidRoomId)
        }

        assertEquals("roomId", ex.parameterName)
        assertEquals("not-a-valid-uuid", ex.invalidValue)
        verifyNoInteractions(chatRoomService, chatMessageService)
    }

    @Test
    fun `getChatMessages throws InvalidUuidException when roomId is invalid`() {
        val invalidRoomId = ChatRoomId("not-a-valid-uuid")

        val ex = assertThrows<InvalidUuidException> {
            controller.getChatMessages(roomId = invalidRoomId)
        }

        assertEquals("roomId", ex.parameterName)
        verifyNoInteractions(chatRoomService, chatMessageService)
    }

    @Test
    fun `leaveChatRoom throws InvalidUuidException when roomId is invalid`() {
        val invalidRoomId = ChatRoomId("not-a-valid-uuid")

        val ex = assertThrows<InvalidUuidException> {
            controller.leaveChatRoom(roomId = invalidRoomId)
        }

        assertEquals("roomId", ex.parameterName)
        verifyNoInteractions(chatRoomService, chatMessageService)
    }

    @Test
    fun `addParticipants throws InvalidUuidException when roomId is invalid`() {
        val invalidRoomId = ChatRoomId("not-a-valid-uuid")
        val request = AddChatParticipantRequest(userIds = listOf(UUID.randomUUID().toString()))

        val ex = assertThrows<InvalidUuidException> {
            controller.addParticipants(roomId = invalidRoomId, body = request)
        }

        assertEquals("roomId", ex.parameterName)
        verifyNoInteractions(chatRoomService, chatMessageService)
    }
}
