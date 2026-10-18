package io.github.neronguyenvn.nerochat.chat.service

import io.github.neronguyenvn.nerochat.chat.domain.event.InternalChatEvent
import io.github.neronguyenvn.nerochat.chat.domain.exception.ChatParticipantNotFoundException
import io.github.neronguyenvn.nerochat.chat.domain.exception.ChatRoomNotFoundException
import io.github.neronguyenvn.nerochat.chat.domain.exception.InvalidChatRoomSizeException
import io.github.neronguyenvn.nerochat.chat.infra.database.model.ChatMessageEntity
import io.github.neronguyenvn.nerochat.chat.infra.database.model.ChatParticipantEntity
import io.github.neronguyenvn.nerochat.chat.infra.database.model.ChatRoomEntity
import io.github.neronguyenvn.nerochat.chat.infra.database.model.DirectPairKey
import io.github.neronguyenvn.nerochat.chat.infra.database.repository.ChatMessageRepository
import io.github.neronguyenvn.nerochat.chat.infra.database.repository.ChatParticipantRepository
import io.github.neronguyenvn.nerochat.chat.infra.database.repository.ChatRoomRepository
import io.github.neronguyenvn.nerochat.contract.chat.ChatRoomType
import io.github.neronguyenvn.nerochat.domain.exception.ForbiddenException
import io.github.neronguyenvn.nerochat.domain.type.ChatRoomId
import io.github.neronguyenvn.nerochat.domain.type.UserId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.context.ApplicationEventPublisher
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.domain.PageRequest
import java.time.Instant
import java.util.Optional
import java.util.UUID
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
class ChatRoomServiceTest {

    private lateinit var chatParticipantRepository: ChatParticipantRepository
    private lateinit var chatRoomRepository: ChatRoomRepository
    private lateinit var chatMessageRepository: ChatMessageRepository
    private lateinit var applicationEventPublisher: ApplicationEventPublisher
    private lateinit var chatRoomService: ChatRoomService

    private val creatorId = UserId(UUID.randomUUID())
    private val targetUserId = UserId(UUID.randomUUID())
    private val user3Id = UserId(UUID.randomUUID())

    private lateinit var creatorEntity: ChatParticipantEntity
    private lateinit var targetEntity: ChatParticipantEntity
    private lateinit var user3Entity: ChatParticipantEntity

    @BeforeEach
    fun setUp() {
        chatParticipantRepository = mock(ChatParticipantRepository::class.java)
        chatRoomRepository = mock(ChatRoomRepository::class.java)
        chatMessageRepository = mock(ChatMessageRepository::class.java)
        applicationEventPublisher = mock(ApplicationEventPublisher::class.java)

        chatRoomService = ChatRoomService(
            chatParticipantRepository = chatParticipantRepository,
            chatRoomRepository = chatRoomRepository,
            chatMessageRepository = chatMessageRepository,
            applicationEventPublisher = applicationEventPublisher,
        )

        creatorEntity = ChatParticipantEntity(
            userId = creatorId.asUUID(),
            email = "creator@example.com",
            displayName = "Creator User"
        )
        targetEntity = ChatParticipantEntity(
            userId = targetUserId.asUUID(),
            email = "target@example.com",
            displayName = "Target User"
        )
        user3Entity = ChatParticipantEntity(
            userId = user3Id.asUUID(),
            email = "user3@example.com",
            displayName = "User 3"
        )
    }

    @Test
    fun `creates direct chat room when not existing`() {
        `when`(chatParticipantRepository.findById(creatorId.asUUID())).thenReturn(Optional.of(creatorEntity))
        `when`(chatParticipantRepository.findById(targetUserId.asUUID())).thenReturn(Optional.of(targetEntity))
        val directPairKey = DirectPairKey.of(creatorId, targetUserId).value
        `when`(chatRoomRepository.findDirectChatRoomBetween(directPairKey)).thenReturn(null)

        val roomId = UUID.randomUUID()
        val createdRoomEntity = ChatRoomEntity(
            id = roomId,
            type = ChatRoomType.DIRECT,
            directPairKey = directPairKey,
            creator = creatorEntity,
            participants = setOf(creatorEntity, targetEntity),
            createdAt = Instant.now()
        )
        `when`(chatRoomRepository.save(any(ChatRoomEntity::class.java))).thenReturn(
            createdRoomEntity
        )

        val messageId = UUID.randomUUID()
        val createdMessageEntity = ChatMessageEntity(
            id = messageId,
            roomId = roomId,
            sender = creatorEntity,
            content = "Hello there",
            createdAt = Instant.now()
        )
        `when`(chatMessageRepository.save(any(ChatMessageEntity::class.java))).thenReturn(createdMessageEntity)

        val result = chatRoomService.createDirectChatRoom(
            creatorId = creatorId,
            targetUserId = targetUserId,
            message = "Hello there"
        )

        assertNotNull(result)
        assertEquals(roomId.toString(), result.id.value)
        assertEquals(ChatRoomType.DIRECT, result.type)
        assertNull(result.name)
        assertEquals(creatorId.value, result.creator.userId.value)
        assertEquals(2, result.participants.size)
        assertNotNull(result.lastMessage)
        assertEquals("Hello there", result.lastMessage?.content)

        verify(chatRoomRepository).save(any(ChatRoomEntity::class.java))
        verify(chatMessageRepository).save(any(ChatMessageEntity::class.java))

        val captor = ArgumentCaptor.forClass(InternalChatEvent.ChatRoomCreatedEvent::class.java)
        verify(applicationEventPublisher).publishEvent(captor.capture())
        assertEquals(roomId.toString(), captor.value.chatRoom.id.value)
    }

    @Test
    fun `idempotently returns existing direct chat room without creating a new one`() {
        `when`(chatParticipantRepository.findById(creatorId.asUUID())).thenReturn(Optional.of(creatorEntity))
        `when`(chatParticipantRepository.findById(targetUserId.asUUID())).thenReturn(Optional.of(targetEntity))

        val existingRoomId = UUID.randomUUID()
        val directPairKey = DirectPairKey.of(creatorId, targetUserId).value
        val existingRoomEntity = ChatRoomEntity(
            id = existingRoomId,
            type = ChatRoomType.DIRECT,
            directPairKey = directPairKey,
            creator = creatorEntity,
            participants = setOf(creatorEntity, targetEntity),
            createdAt = Instant.now()
        )
        `when`(chatRoomRepository.findDirectChatRoomBetween(directPairKey)).thenReturn(
            existingRoomEntity
        )

        val existingMessageEntity = ChatMessageEntity(
            id = UUID.randomUUID(),
            roomId = existingRoomId,
            sender = creatorEntity,
            content = "Previous message",
            createdAt = Instant.now()
        )
        `when`(chatMessageRepository.findLatestMessagesByRoomIds(setOf(existingRoomId)))
            .thenReturn(listOf(existingMessageEntity))

        val result = chatRoomService.createDirectChatRoom(
            creatorId = creatorId,
            targetUserId = targetUserId,
            message = "Some message"
        )

        assertEquals(existingRoomId.toString(), result.id.value)
        assertEquals(ChatRoomType.DIRECT, result.type)
        assertEquals("Previous message", result.lastMessage?.content)

        verify(chatRoomRepository, never()).save(any(ChatRoomEntity::class.java))
        verify(chatMessageRepository, never()).save(any(ChatMessageEntity::class.java))
        verify(applicationEventPublisher, never()).publishEvent(any())
    }

    @Test
    fun `createDirectChatRoom throws DataIntegrityViolationException when repository save throws exception`() {
        `when`(chatParticipantRepository.findById(creatorId.asUUID())).thenReturn(
            Optional.of(
                creatorEntity
            )
        )
        `when`(chatParticipantRepository.findById(targetUserId.asUUID())).thenReturn(
            Optional.of(
                targetEntity
            )
        )

        val directPairKey = DirectPairKey.of(creatorId, targetUserId).value
        `when`(chatRoomRepository.findDirectChatRoomBetween(directPairKey)).thenReturn(null)
        `when`(chatRoomRepository.save(any(ChatRoomEntity::class.java)))
            .thenThrow(DataIntegrityViolationException("Duplicate entry"))

        assertThrows<DataIntegrityViolationException> {
            chatRoomService.createDirectChatRoom(
                creatorId = creatorId,
                targetUserId = targetUserId,
                message = "Some message"
            )
        }

        verify(chatRoomRepository).save(any(ChatRoomEntity::class.java))
        verify(chatMessageRepository, never()).save(any(ChatMessageEntity::class.java))
        verify(applicationEventPublisher, never()).publishEvent(any())
    }

    @Test
    fun `throws InvalidChatRoomSizeException when creatorId equals targetUserId`() {
        assertThrows<InvalidChatRoomSizeException> {
            chatRoomService.createDirectChatRoom(
                creatorId = creatorId,
                targetUserId = creatorId,
                message = "Hello"
            )
        }
    }

    @Test
    fun `createDirectChatRoom throws ChatParticipantNotFoundException when creator does not exist`() {
        `when`(chatParticipantRepository.findById(creatorId.asUUID())).thenReturn(Optional.empty())

        assertThrows<ChatParticipantNotFoundException> {
            chatRoomService.createDirectChatRoom(
                creatorId = creatorId,
                targetUserId = targetUserId,
                message = "Hello"
            )
        }
    }

    @Test
    fun `createDirectChatRoom throws ChatParticipantNotFoundException when target user does not exist`() {
        `when`(chatParticipantRepository.findById(creatorId.asUUID())).thenReturn(Optional.of(creatorEntity))
        `when`(chatParticipantRepository.findById(targetUserId.asUUID())).thenReturn(Optional.empty())

        assertThrows<ChatParticipantNotFoundException> {
            chatRoomService.createDirectChatRoom(
                creatorId = creatorId,
                targetUserId = targetUserId,
                message = "Hello"
            )
        }
    }

    @Test
    fun `creates group chat room successfully with null lastMessage`() {
        `when`(chatParticipantRepository.findById(creatorId.asUUID())).thenReturn(Optional.of(creatorEntity))
        `when`(chatParticipantRepository.findByUserIdIn(setOf(targetUserId.asUUID(), user3Id.asUUID())))
            .thenReturn(setOf(targetEntity, user3Entity))

        val roomId = UUID.randomUUID()
        val createdGroupEntity = ChatRoomEntity(
            id = roomId,
            type = ChatRoomType.GROUP,
            name = "Project Team",
            creator = creatorEntity,
            participants = setOf(creatorEntity, targetEntity, user3Entity),
            createdAt = Instant.now()
        )
        `when`(chatRoomRepository.save(any(ChatRoomEntity::class.java))).thenReturn(createdGroupEntity)

        val result = chatRoomService.createGroupChatRoom(
            creatorId = creatorId,
            name = "Project Team",
            participantIds = listOf(targetUserId, user3Id)
        )

        assertNotNull(result)
        assertEquals(roomId.toString(), result.id.value)
        assertEquals(ChatRoomType.GROUP, result.type)
        assertEquals("Project Team", result.name)
        assertEquals(creatorId.value, result.creator.userId.value)
        assertEquals(3, result.participants.size)
        assertNull(result.lastMessage)

        verify(chatRoomRepository).save(any(ChatRoomEntity::class.java))

        val captor = ArgumentCaptor.forClass(InternalChatEvent.ChatRoomCreatedEvent::class.java)
        verify(applicationEventPublisher).publishEvent(captor.capture())
        assertEquals(roomId.toString(), captor.value.chatRoom.id.value)
    }

    @Test
    fun `createGroupChatRoom throws InvalidChatRoomSizeException when fewer than 2 participants provided`() {
        assertThrows<InvalidChatRoomSizeException> {
            chatRoomService.createGroupChatRoom(
                creatorId = creatorId,
                name = "Group",
                participantIds = listOf(targetUserId)
            )
        }

        assertThrows<InvalidChatRoomSizeException> {
            chatRoomService.createGroupChatRoom(
                creatorId = creatorId,
                name = "Group",
                participantIds = emptyList()
            )
        }
    }

    @Test
    fun `createGroupChatRoom throws InvalidChatRoomSizeException when duplicate participants result in less than 2 unique`() {
        assertThrows<InvalidChatRoomSizeException> {
            chatRoomService.createGroupChatRoom(
                creatorId = creatorId,
                name = "Group",
                participantIds = listOf(targetUserId, targetUserId)
            )
        }

        assertThrows<InvalidChatRoomSizeException> {
            chatRoomService.createGroupChatRoom(
                creatorId = creatorId,
                name = "Group",
                participantIds = listOf(creatorId, targetUserId)
            )
        }
    }

    @Test
    fun `createGroupChatRoom throws ChatParticipantNotFoundException when creator does not exist`() {
        `when`(chatParticipantRepository.findById(creatorId.asUUID())).thenReturn(Optional.empty())

        assertThrows<ChatParticipantNotFoundException> {
            chatRoomService.createGroupChatRoom(
                creatorId = creatorId,
                name = "Group",
                participantIds = listOf(targetUserId, user3Id)
            )
        }
    }

    @Test
    fun `createGroupChatRoom throws ChatParticipantNotFoundException when a participant does not exist`() {
        `when`(chatParticipantRepository.findById(creatorId.asUUID())).thenReturn(Optional.of(creatorEntity))
        `when`(chatParticipantRepository.findByUserIdIn(setOf(targetUserId.asUUID(), user3Id.asUUID())))
            .thenReturn(setOf(targetEntity))

        assertThrows<ChatParticipantNotFoundException> {
            chatRoomService.createGroupChatRoom(
                creatorId = creatorId,
                name = "Group",
                participantIds = listOf(targetUserId, user3Id)
            )
        }
    }

    @Test
    fun `addParticipantsToChatRoom throws ForbiddenException when chatRoom type is DIRECT`() {
        val roomId = ChatRoomId(UUID.randomUUID())
        val roomEntity = ChatRoomEntity(
            id = roomId.asUUID(),
            type = ChatRoomType.DIRECT,
            creator = creatorEntity,
            participants = setOf(creatorEntity, targetEntity),
            createdAt = Instant.now()
        )
        `when`(chatRoomRepository.findById(roomId.asUUID())).thenReturn(Optional.of(roomEntity))

        assertThrows<ForbiddenException> {
            chatRoomService.addParticipantsToChatRoom(
                roomId = roomId,
                requesterId = creatorId,
                userIds = setOf(user3Id)
            )
        }

        verify(chatRoomRepository, never()).save(any())
    }

    @Test
    fun `addParticipantsToChatRoom successfully adds participants to GROUP chat room and publishes ChatParticipantJoinedEvent`() {
        val roomId = ChatRoomId(UUID.randomUUID())
        val roomEntity = ChatRoomEntity(
            id = roomId.asUUID(),
            type = ChatRoomType.GROUP,
            name = "Group Chat",
            creator = creatorEntity,
            participants = setOf(creatorEntity, targetEntity),
            createdAt = Instant.now()
        )
        `when`(chatRoomRepository.findById(roomId.asUUID())).thenReturn(Optional.of(roomEntity))
        `when`(chatParticipantRepository.findById(user3Id.asUUID())).thenReturn(
            Optional.of(
                user3Entity
            )
        )
        `when`(chatRoomRepository.save(any(ChatRoomEntity::class.java))).thenReturn(roomEntity)

        val result = chatRoomService.addParticipantsToChatRoom(
            roomId = roomId,
            requesterId = creatorId,
            userIds = setOf(user3Id)
        )

        assertNotNull(result)
        verify(chatRoomRepository).save(any(ChatRoomEntity::class.java))

        val captor =
            ArgumentCaptor.forClass(InternalChatEvent.ChatParticipantJoinedEvent::class.java)
        verify(applicationEventPublisher).publishEvent(captor.capture())
        assertEquals(roomId, captor.value.roomId)
        assertEquals(1, captor.value.newUsers.size)
        assertEquals(user3Id.value, captor.value.newUsers.first().userId.value)
    }

    @Test
    fun `addParticipantsToChatRoom throws ForbiddenException when requester is not in chat room`() {
        val roomId = ChatRoomId(UUID.randomUUID())
        val roomEntity = ChatRoomEntity(
            id = roomId.asUUID(),
            type = ChatRoomType.GROUP,
            name = "Group Chat",
            creator = creatorEntity,
            participants = setOf(creatorEntity, targetEntity),
            createdAt = Instant.now()
        )
        `when`(chatRoomRepository.findById(roomId.asUUID())).thenReturn(Optional.of(roomEntity))

        assertThrows<ForbiddenException> {
            chatRoomService.addParticipantsToChatRoom(
                roomId = roomId,
                requesterId = user3Id,
                userIds = setOf(user3Id)
            )
        }
    }

    @Test
    fun `removeParticipantFromChatRoom deletes direct chat room and publishes ChatParticipantLeftEvent`() {
        val roomId = ChatRoomId(UUID.randomUUID())
        val roomEntity = ChatRoomEntity(
            id = roomId.asUUID(),
            type = ChatRoomType.DIRECT,
            creator = creatorEntity,
            participants = setOf(creatorEntity, targetEntity),
            createdAt = Instant.now()
        )
        `when`(chatRoomRepository.findById(roomId.asUUID())).thenReturn(Optional.of(roomEntity))

        chatRoomService.removeParticipantFromChatRoom(
            roomId = roomId,
            userId = creatorId
        )

        verify(chatRoomRepository).deleteById(roomId.asUUID())
        verify(chatRoomRepository, never()).save(any())

        val captor = ArgumentCaptor.forClass(InternalChatEvent.ChatParticipantLeftEvent::class.java)
        verify(applicationEventPublisher).publishEvent(captor.capture())
        assertEquals(roomId, captor.value.roomId)
        assertEquals(creatorId.value, captor.value.leftUser.userId.value)
    }

    @Test
    fun `removeParticipantFromChatRoom deletes group chat room when last participant leaves and publishes ChatParticipantLeftEvent`() {
        val roomId = ChatRoomId(UUID.randomUUID())
        val roomEntity = ChatRoomEntity(
            id = roomId.asUUID(),
            type = ChatRoomType.GROUP,
            name = "Group Chat",
            creator = creatorEntity,
            participants = setOf(creatorEntity),
            createdAt = Instant.now()
        )
        `when`(chatRoomRepository.findById(roomId.asUUID())).thenReturn(Optional.of(roomEntity))

        chatRoomService.removeParticipantFromChatRoom(
            roomId = roomId,
            userId = creatorId
        )

        verify(chatRoomRepository).deleteById(roomId.asUUID())
        verify(chatRoomRepository, never()).save(any())

        val captor = ArgumentCaptor.forClass(InternalChatEvent.ChatParticipantLeftEvent::class.java)
        verify(applicationEventPublisher).publishEvent(captor.capture())
        assertEquals(roomId, captor.value.roomId)
        assertEquals(creatorId.value, captor.value.leftUser.userId.value)
    }

    @Test
    fun `removeParticipantFromChatRoom removes participant from group chat room and publishes ChatParticipantLeftEvent`() {
        val roomId = ChatRoomId(UUID.randomUUID())
        val roomEntity = ChatRoomEntity(
            id = roomId.asUUID(),
            type = ChatRoomType.GROUP,
            name = "Group Chat",
            creator = creatorEntity,
            participants = setOf(creatorEntity, targetEntity, user3Entity),
            createdAt = Instant.now()
        )
        `when`(chatRoomRepository.findById(roomId.asUUID())).thenReturn(Optional.of(roomEntity))
        `when`(chatRoomRepository.save(any(ChatRoomEntity::class.java))).thenReturn(roomEntity)

        chatRoomService.removeParticipantFromChatRoom(
            roomId = roomId,
            userId = targetUserId
        )

        verify(chatRoomRepository, never()).deleteById(any())
        verify(chatRoomRepository).save(roomEntity)

        val captor = ArgumentCaptor.forClass(InternalChatEvent.ChatParticipantLeftEvent::class.java)
        verify(applicationEventPublisher).publishEvent(captor.capture())
        assertEquals(roomId, captor.value.roomId)
        assertEquals(targetUserId.value, captor.value.leftUser.userId.value)
    }

    @Test
    fun `removeParticipantFromChatRoom throws ChatRoomNotFoundException when chat room not found`() {
        val roomId = ChatRoomId(UUID.randomUUID())
        `when`(chatRoomRepository.findById(roomId.asUUID())).thenReturn(Optional.empty())

        assertThrows<ChatRoomNotFoundException> {
            chatRoomService.removeParticipantFromChatRoom(
                roomId = roomId,
                userId = creatorId
            )
        }
    }

    @Test
    fun `removeParticipantFromChatRoom throws ChatParticipantNotFoundException when participant not in chat room`() {
        val roomId = ChatRoomId(UUID.randomUUID())
        val roomEntity = ChatRoomEntity(
            id = roomId.asUUID(),
            type = ChatRoomType.GROUP,
            name = "Group Chat",
            creator = creatorEntity,
            participants = setOf(creatorEntity),
            createdAt = Instant.now()
        )
        `when`(chatRoomRepository.findById(roomId.asUUID())).thenReturn(Optional.of(roomEntity))

        assertThrows<ChatParticipantNotFoundException> {
            chatRoomService.removeParticipantFromChatRoom(
                roomId = roomId,
                userId = targetUserId
            )
        }
    }

    @Test
    fun `getChatMessages calls findByRoomId when before is null`() {
        val roomId = ChatRoomId(UUID.randomUUID())
        val message = ChatMessageEntity(
            id = UUID.randomUUID(),
            roomId = roomId.asUUID(),
            sender = creatorEntity,
            content = "Hello",
            createdAt = Instant.now()
        )
        `when`(
            chatMessageRepository.findByRoomId(
                any() ?: roomId.asUUID(),
                any() ?: PageRequest.of(0, 20)
            )
        )
            .thenReturn(org.springframework.data.domain.SliceImpl(listOf(message)))

        val result = chatRoomService.getChatMessages(roomId = roomId, before = null)

        assertEquals(1, result.messages.size)
        assertEquals("Hello", result.messages.first().content)
        verify(chatMessageRepository).findByRoomId(
            any() ?: roomId.asUUID(),
            any() ?: PageRequest.of(0, 20)
        )
        verify(chatMessageRepository, never()).findByRoomIdBefore(
            any() ?: roomId.asUUID(),
            any() ?: Instant.EPOCH,
            any() ?: PageRequest.of(0, 20)
        )
    }

    @Test
    fun `getChatMessages calls findByRoomIdBefore when before is provided`() {
        val roomId = ChatRoomId(UUID.randomUUID())
        val before = Instant.now()
        val message = ChatMessageEntity(
            id = UUID.randomUUID(),
            roomId = roomId.asUUID(),
            sender = creatorEntity,
            content = "Old Hello",
            createdAt = before.minusSeconds(60)
        )
        `when`(
            chatMessageRepository.findByRoomIdBefore(
                any() ?: roomId.asUUID(),
                any() ?: before,
                any() ?: PageRequest.of(0, 20)
            )
        ).thenReturn(org.springframework.data.domain.SliceImpl(listOf(message)))

        val result = chatRoomService.getChatMessages(roomId = roomId, before = before)

        assertEquals(1, result.messages.size)
        assertEquals("Old Hello", result.messages.first().content)
        verify(chatMessageRepository).findByRoomIdBefore(
            any() ?: roomId.asUUID(),
            any() ?: before,
            any() ?: PageRequest.of(0, 20)
        )
        verify(chatMessageRepository, never()).findByRoomId(
            any() ?: roomId.asUUID(),
            any() ?: PageRequest.of(0, 20)
        )
    }
}
