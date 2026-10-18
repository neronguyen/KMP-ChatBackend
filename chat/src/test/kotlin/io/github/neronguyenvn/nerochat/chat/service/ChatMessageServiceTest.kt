package io.github.neronguyenvn.nerochat.chat.service

import io.github.neronguyenvn.nerochat.chat.domain.exception.DuplicateMessageIdException
import io.github.neronguyenvn.nerochat.chat.infra.database.model.ChatMessageEntity
import io.github.neronguyenvn.nerochat.chat.infra.database.model.ChatParticipantEntity
import io.github.neronguyenvn.nerochat.chat.infra.database.model.ChatRoomEntity
import io.github.neronguyenvn.nerochat.chat.infra.database.repository.ChatMessageRepository
import io.github.neronguyenvn.nerochat.chat.infra.database.repository.ChatParticipantRepository
import io.github.neronguyenvn.nerochat.chat.infra.database.repository.ChatRoomRepository
import io.github.neronguyenvn.nerochat.contract.chat.ChatRoomType
import io.github.neronguyenvn.nerochat.domain.event.ChatEvent
import io.github.neronguyenvn.nerochat.domain.type.ChatMessageId
import io.github.neronguyenvn.nerochat.domain.type.ChatRoomId
import io.github.neronguyenvn.nerochat.domain.type.UserId
import io.github.neronguyenvn.nerochat.infra.messagequeue.EventPublisher
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.cache.CacheManager
import org.springframework.context.ApplicationEventPublisher
import java.time.Instant
import java.util.Optional
import java.util.UUID

class ChatMessageServiceTest {

    private lateinit var chatRoomRepository: ChatRoomRepository
    private lateinit var chatMessageRepository: ChatMessageRepository
    private lateinit var chatParticipantRepository: ChatParticipantRepository
    private lateinit var eventPublisher: EventPublisher
    private lateinit var applicationEventPublisher: ApplicationEventPublisher
    private lateinit var cacheManager: CacheManager
    private lateinit var chatMessageService: ChatMessageService

    private val roomId = ChatRoomId(UUID.randomUUID())
    private val otherRoomId = ChatRoomId(UUID.randomUUID())
    private val senderId = UserId(UUID.randomUUID())
    private val otherSenderId = UserId(UUID.randomUUID())

    private lateinit var senderEntity: ChatParticipantEntity
    private lateinit var otherSenderEntity: ChatParticipantEntity
    private lateinit var roomEntity: ChatRoomEntity

    @BeforeEach
    fun setUp() {
        chatRoomRepository = mock(ChatRoomRepository::class.java)
        chatMessageRepository = mock(ChatMessageRepository::class.java)
        chatParticipantRepository = mock(ChatParticipantRepository::class.java)
        eventPublisher = mock(EventPublisher::class.java)
        applicationEventPublisher = mock(ApplicationEventPublisher::class.java)
        cacheManager = mock(CacheManager::class.java)

        chatMessageService = ChatMessageService(
            chatRoomRepository = chatRoomRepository,
            chatMessageRepository = chatMessageRepository,
            chatParticipantRepository = chatParticipantRepository,
            eventPublisher = eventPublisher,
            applicationEventPublisher = applicationEventPublisher,
            cacheManager = cacheManager
        )

        senderEntity = ChatParticipantEntity(
            userId = senderId.asUUID(),
            email = "sender@example.com",
            displayName = "Sender"
        )
        otherSenderEntity = ChatParticipantEntity(
            userId = otherSenderId.asUUID(),
            email = "other@example.com",
            displayName = "Other Sender"
        )
        roomEntity = ChatRoomEntity(
            id = roomId.asUUID(),
            type = ChatRoomType.DIRECT,
            creator = senderEntity,
            participants = setOf(senderEntity, otherSenderEntity),
            createdAt = Instant.now()
        )
    }

    @Test
    fun `sendMessage persists message with client-assigned UUID and publishes event`() {
        val clientMessageId = ChatMessageId(UUID.randomUUID().toString())
        `when`(chatRoomRepository.findChatRoomById(roomId.asUUID(), senderId.asUUID()))
            .thenReturn(roomEntity)
        `when`(chatParticipantRepository.findById(senderId.asUUID()))
            .thenReturn(Optional.of(senderEntity))
        `when`(chatMessageRepository.findById(clientMessageId.asUUID()))
            .thenReturn(Optional.empty())
        `when`(chatMessageRepository.saveAndFlush(any(ChatMessageEntity::class.java)))
            .thenAnswer { invocation -> invocation.getArgument<ChatMessageEntity>(0) }

        val result = chatMessageService.sendMessage(
            roomId = roomId,
            senderId = senderId,
            content = "  Hello world  ",
            messageId = clientMessageId
        )

        assertEquals(clientMessageId, result.id)
        assertEquals(roomId, result.roomId)
        assertEquals("Hello world", result.content)
        assertEquals(senderId, result.sender.userId)

        val entityCaptor = ArgumentCaptor.forClass(ChatMessageEntity::class.java)
        verify(chatMessageRepository).saveAndFlush(entityCaptor.capture())
        assertEquals(clientMessageId.asUUID(), entityCaptor.value.id)
        assertEquals("Hello world", entityCaptor.value.content)

        verify(eventPublisher).publish(
            ChatEvent.NewMessage(
                roomId = roomId,
                senderId = senderId,
                message = "Hello world"
            )
        )
    }

    @Test
    fun `sendMessage generates application UUID when messageId is null`() {
        `when`(chatRoomRepository.findChatRoomById(roomId.asUUID(), senderId.asUUID()))
            .thenReturn(roomEntity)
        `when`(chatParticipantRepository.findById(senderId.asUUID()))
            .thenReturn(Optional.of(senderEntity))
        `when`(chatMessageRepository.saveAndFlush(any(ChatMessageEntity::class.java)))
            .thenAnswer { invocation -> invocation.getArgument<ChatMessageEntity>(0) }

        val result = chatMessageService.sendMessage(
            roomId = roomId,
            senderId = senderId,
            content = "Server generated ID message",
            messageId = null
        )

        assertNotNull(result.id.asUUID())
        assertEquals("Server generated ID message", result.content)

        val entityCaptor = ArgumentCaptor.forClass(ChatMessageEntity::class.java)
        verify(chatMessageRepository).saveAndFlush(entityCaptor.capture())
        assertEquals(result.id.asUUID(), entityCaptor.value.id)
    }

    @Test
    fun `sendMessage is idempotent when existing message has same roomId and senderId`() {
        val clientMessageId = ChatMessageId(UUID.randomUUID().toString())
        val existingEntity = ChatMessageEntity(
            id = clientMessageId.asUUID(),
            roomId = roomId.asUUID(),
            sender = senderEntity,
            content = "Original content"
        )

        `when`(chatRoomRepository.findChatRoomById(roomId.asUUID(), senderId.asUUID()))
            .thenReturn(roomEntity)
        `when`(chatParticipantRepository.findById(senderId.asUUID()))
            .thenReturn(Optional.of(senderEntity))
        `when`(chatMessageRepository.findById(clientMessageId.asUUID()))
            .thenReturn(Optional.of(existingEntity))

        val result = chatMessageService.sendMessage(
            roomId = roomId,
            senderId = senderId,
            content = "Retry attempt",
            messageId = clientMessageId
        )

        assertEquals(clientMessageId, result.id)
        assertEquals("Original content", result.content)

        verify(chatMessageRepository, never()).saveAndFlush(any(ChatMessageEntity::class.java))
        verifyNoInteractions(eventPublisher)
    }

    @Test
    fun `sendMessage throws DuplicateMessageIdException when existing message belongs to another sender`() {
        val clientMessageId = ChatMessageId(UUID.randomUUID().toString())
        val existingEntity = ChatMessageEntity(
            id = clientMessageId.asUUID(),
            roomId = roomId.asUUID(),
            sender = otherSenderEntity,
            content = "Other user's message"
        )

        `when`(chatRoomRepository.findChatRoomById(roomId.asUUID(), senderId.asUUID()))
            .thenReturn(roomEntity)
        `when`(chatParticipantRepository.findById(senderId.asUUID()))
            .thenReturn(Optional.of(senderEntity))
        `when`(chatMessageRepository.findById(clientMessageId.asUUID()))
            .thenReturn(Optional.of(existingEntity))

        assertThrows<DuplicateMessageIdException> {
            chatMessageService.sendMessage(
                roomId = roomId,
                senderId = senderId,
                content = "Attempted overwrite",
                messageId = clientMessageId
            )
        }

        verify(chatMessageRepository, never()).saveAndFlush(any(ChatMessageEntity::class.java))
        verifyNoInteractions(eventPublisher)
    }

    @Test
    fun `sendMessage throws DuplicateMessageIdException when existing message belongs to another room`() {
        val clientMessageId = ChatMessageId(UUID.randomUUID().toString())
        val existingEntity = ChatMessageEntity(
            id = clientMessageId.asUUID(),
            roomId = otherRoomId.asUUID(),
            sender = senderEntity,
            content = "Message in another room"
        )

        `when`(chatRoomRepository.findChatRoomById(roomId.asUUID(), senderId.asUUID()))
            .thenReturn(roomEntity)
        `when`(chatParticipantRepository.findById(senderId.asUUID()))
            .thenReturn(Optional.of(senderEntity))
        `when`(chatMessageRepository.findById(clientMessageId.asUUID()))
            .thenReturn(Optional.of(existingEntity))

        assertThrows<DuplicateMessageIdException> {
            chatMessageService.sendMessage(
                roomId = roomId,
                senderId = senderId,
                content = "Attempted cross-room overwrite",
                messageId = clientMessageId
            )
        }

        verify(chatMessageRepository, never()).saveAndFlush(any(ChatMessageEntity::class.java))
        verifyNoInteractions(eventPublisher)
    }
}
