package io.github.neronguyenvn.nerochat.chat.service

import io.github.neronguyenvn.nerochat.chat.domain.event.InternalChatEvent
import io.github.neronguyenvn.nerochat.chat.domain.exception.ChatParticipantNotFoundException
import io.github.neronguyenvn.nerochat.chat.domain.exception.ChatRoomNotFoundException
import io.github.neronguyenvn.nerochat.chat.domain.exception.DirectChatRoomModificationException
import io.github.neronguyenvn.nerochat.chat.domain.exception.InvalidChatRoomSizeException
import io.github.neronguyenvn.nerochat.chat.domain.exception.ParticipantAlreadyInRoomException
import io.github.neronguyenvn.nerochat.chat.domain.exception.ParticipantNotInRoomException
import io.github.neronguyenvn.nerochat.chat.domain.model.ChatMessage
import io.github.neronguyenvn.nerochat.chat.domain.model.ChatRoom
import io.github.neronguyenvn.nerochat.chat.infra.database.model.ChatRoomEntity
import io.github.neronguyenvn.nerochat.chat.infra.database.model.DirectPairKey
import io.github.neronguyenvn.nerochat.chat.infra.database.model.asExternalModel
import io.github.neronguyenvn.nerochat.chat.infra.database.repository.ChatMessageRepository
import io.github.neronguyenvn.nerochat.chat.infra.database.repository.ChatParticipantRepository
import io.github.neronguyenvn.nerochat.chat.infra.database.repository.ChatRoomRepository
import io.github.neronguyenvn.nerochat.contract.chat.ChatRoomType
import io.github.neronguyenvn.nerochat.domain.type.ChatRoomId
import io.github.neronguyenvn.nerochat.domain.type.UserId
import org.springframework.context.ApplicationEventPublisher
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
@Service
class ChatRoomService(
    private val chatParticipantRepository: ChatParticipantRepository,
    private val chatRoomRepository: ChatRoomRepository,
    private val chatMessageRepository: ChatMessageRepository,
    private val applicationEventPublisher: ApplicationEventPublisher,
    private val directChatRoomCreationTxHelper: DirectChatRoomCreationTxHelper,
) {
    fun existsById(roomId: ChatRoomId): Boolean =
        chatRoomRepository.existsById(roomId.asUUID())

    fun getChatRoomById(
        roomId: ChatRoomId,
        requesterId: UserId
    ): ChatRoom? {
        return chatRoomRepository
            .findChatRoomById(roomId.asUUID(), requesterId.asUUID())
            ?.asExternalModel(lastMessage = findLastMessageOfChatRoom(roomId = roomId))
    }

    fun findChatRoomsByUser(userId: UserId): List<ChatRoom> {
        val chatRoomEntities = chatRoomRepository.findAllByUserId(userId.asUUID())
        val roomIds = chatRoomEntities.mapNotNull { it.id }.toSet()

        val latestMessages = chatMessageRepository
            .findLatestMessagesByRoomIds(roomIds)
            .associateBy { it.roomId }

        return chatRoomEntities
            .map { chatRoomEntity ->
                val lastMessage = latestMessages[chatRoomEntity.id]?.asExternalModel()
                chatRoomEntity.asExternalModel(lastMessage = lastMessage)
            }
            .sortedByDescending { it.lastActivityAt }
    }

    fun createDirectChatRoom(
        creatorId: UserId,
        targetUserId: UserId,
        message: String,
    ): ChatRoom {
        if (creatorId == targetUserId) {
            throw InvalidChatRoomSizeException("Cannot create a direct chat room with yourself")
        }

        val creator = chatParticipantRepository.findByIdOrNull(creatorId.asUUID())
            ?: throw ChatParticipantNotFoundException(creatorId)

        val target = chatParticipantRepository.findByIdOrNull(targetUserId.asUUID())
            ?: throw ChatParticipantNotFoundException(targetUserId)

        val directPairKey = DirectPairKey.of(creatorId, targetUserId).value
        findExistingDirectChatRoom(directPairKey)?.let { return it }

        return try {
            directChatRoomCreationTxHelper.createDirectChatRoom(
                directPairKey = directPairKey,
                creator = creator,
                target = target,
                message = message,
            )
        } catch (ex: DataIntegrityViolationException) {
            findExistingDirectChatRoom(directPairKey) ?: throw ex
        }
    }

    private fun findExistingDirectChatRoom(directPairKey: String): ChatRoom? {
        val existingRoom = chatRoomRepository.findDirectChatRoomBetween(directPairKey) ?: return null
        val roomId = existingRoom.id ?: error("ChatRoomId must be generated")
        val lastMessage = chatMessageRepository
            .findLatestMessagesByRoomIds(setOf(roomId))
            .firstOrNull()
            ?.asExternalModel()

        return existingRoom.asExternalModel(lastMessage = lastMessage)
    }

    @Transactional
    fun createGroupChatRoom(
        creatorId: UserId,
        name: String? = null,
        participantIds: List<UserId>,
    ): ChatRoom {
        val uniqueParticipantIds = participantIds.toSet()
        val otherParticipantIds = uniqueParticipantIds - creatorId
        if (otherParticipantIds.size < 2) {
            throw InvalidChatRoomSizeException("Group chat rooms must have at least 2 other participants")
        }

        val creator = chatParticipantRepository.findByIdOrNull(creatorId.asUUID())
            ?: throw ChatParticipantNotFoundException(creatorId)

        val otherParticipants = chatParticipantRepository.findByUserIdIn(
            otherParticipantIds.map { it.asUUID() }.toSet()
        )

        if (otherParticipants.size != otherParticipantIds.size) {
            val foundIds = otherParticipants.map { UserId(it.userId) }.toSet()
            val missingIds = otherParticipantIds - foundIds
            throw ChatParticipantNotFoundException(missingIds.first())
        }

        val allParticipants = setOf(creator) + otherParticipants

        val groupRoom = chatRoomRepository.save(
            ChatRoomEntity(
                type = ChatRoomType.GROUP,
                name = name?.trim()?.takeIf { it.isNotEmpty() },
                creator = creator,
                participants = allParticipants,
            )
        )

        val createdRoom = groupRoom.asExternalModel(lastMessage = null)

        applicationEventPublisher.publishEvent(
            InternalChatEvent.ChatRoomCreatedEvent(chatRoom = createdRoom)
        )

        return createdRoom
    }

    @Transactional
    fun addParticipantsToChatRoom(
        roomId: ChatRoomId,
        requesterId: UserId,
        userIds: Set<UserId>
    ): ChatRoom {
        val chatRoom = chatRoomRepository.findByIdOrNull(roomId.asUUID())
            ?: throw ChatRoomNotFoundException()

        if (chatRoom.type == ChatRoomType.DIRECT) {
            throw DirectChatRoomModificationException()
        }

        val requester = chatRoom.participants.firstOrNull {
            it.userId == requesterId.asUUID()
        } ?: throw ParticipantNotInRoomException()

        val chatParticipantIds = chatRoom.participants.map { UserId(it.userId) }.toSet()

        val users = userIds.map { userId ->
            if (userId in chatParticipantIds) {
                throw ParticipantAlreadyInRoomException(userId)
            }

            chatParticipantRepository.findByIdOrNull(userId.asUUID())
                ?: throw ChatParticipantNotFoundException(userId)
        }

        val lastMessage = findLastMessageOfChatRoom(roomId = roomId)
        val updatedChatRoom = chatRoomRepository.save(
            chatRoom.apply {
                this.participants = chatRoom.participants + users
            }
        ).asExternalModel(lastMessage = lastMessage)

        applicationEventPublisher.publishEvent(
            InternalChatEvent.ChatParticipantJoinedEvent(
                roomId = roomId,
                newUsers = users.map { it.asExternalModel() }.toSet(),
                addedBy = requester.asExternalModel(),
            )
        )

        return updatedChatRoom
    }

    @Transactional
    fun removeParticipantFromChatRoom(
        roomId: ChatRoomId,
        userId: UserId
    ) {
        val chatRoom = chatRoomRepository.findByIdOrNull(roomId.asUUID())
            ?: throw ChatRoomNotFoundException()

        val participant = chatRoom.participants.find { UserId(it.userId) == userId }
            ?: throw ChatParticipantNotFoundException(userId)

        val newParticipantsSize = chatRoom.participants.size - 1
        if (chatRoom.type == ChatRoomType.DIRECT || newParticipantsSize == 0) {
            chatRoomRepository.deleteById(roomId.asUUID())
        } else {
            chatRoomRepository.save(
                chatRoom.apply {
                    this.participants = chatRoom.participants - participant
                }
            )
        }

        applicationEventPublisher.publishEvent(
            InternalChatEvent.ChatParticipantLeftEvent(
                roomId = roomId,
                leftUser = participant.asExternalModel()
            )
        )
    }

    private fun findLastMessageOfChatRoom(roomId: ChatRoomId): ChatMessage? {
        return chatMessageRepository
            .findLatestMessagesByRoomIds(setOf(roomId.asUUID()))
            .firstOrNull()
            ?.asExternalModel()
    }
}
