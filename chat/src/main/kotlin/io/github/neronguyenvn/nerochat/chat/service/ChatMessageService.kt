package io.github.neronguyenvn.nerochat.chat.service

import io.github.neronguyenvn.nerochat.chat.api.mapper.asDto
import io.github.neronguyenvn.nerochat.chat.domain.event.InternalChatEvent
import io.github.neronguyenvn.nerochat.chat.domain.exception.ChatParticipantNotFoundException
import io.github.neronguyenvn.nerochat.chat.domain.exception.ChatRoomNotFoundException
import io.github.neronguyenvn.nerochat.chat.domain.exception.DuplicateMessageIdException
import io.github.neronguyenvn.nerochat.chat.domain.exception.MessageNotFoundException
import io.github.neronguyenvn.nerochat.chat.domain.exception.NotMessageSenderException
import io.github.neronguyenvn.nerochat.chat.domain.model.ChatMessage
import io.github.neronguyenvn.nerochat.chat.infra.database.model.ChatMessageEntity
import io.github.neronguyenvn.nerochat.chat.infra.database.model.asExternalModel
import io.github.neronguyenvn.nerochat.chat.infra.database.repository.ChatMessageRepository
import io.github.neronguyenvn.nerochat.chat.infra.database.repository.ChatParticipantRepository
import io.github.neronguyenvn.nerochat.chat.infra.database.repository.ChatRoomRepository
import io.github.neronguyenvn.nerochat.contract.chat.ChatMessageSliceDto
import io.github.neronguyenvn.nerochat.domain.event.ChatEvent
import io.github.neronguyenvn.nerochat.domain.type.ChatMessageId
import io.github.neronguyenvn.nerochat.domain.type.ChatRoomId
import io.github.neronguyenvn.nerochat.domain.type.UserId
import io.github.neronguyenvn.nerochat.infra.caching.CacheNames
import io.github.neronguyenvn.nerochat.infra.messagequeue.EventPublisher
import org.springframework.cache.CacheManager
import org.springframework.cache.annotation.CacheEvict
import org.springframework.cache.annotation.Cacheable
import org.springframework.context.ApplicationEventPublisher
import org.springframework.data.domain.PageRequest
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlin.uuid.toJavaUuid

@OptIn(ExperimentalUuidApi::class)
@Service
class ChatMessageService(
    private val chatRoomRepository: ChatRoomRepository,
    private val chatMessageRepository: ChatMessageRepository,
    private val chatParticipantRepository: ChatParticipantRepository,
    private val eventPublisher: EventPublisher,
    private val applicationEventPublisher: ApplicationEventPublisher,
    private val cacheManager: CacheManager
) {
    @Transactional
    @CacheEvict(
        value = [CacheNames.MESSAGES],
        key = "#roomId",
    )
    fun sendMessage(
        roomId: ChatRoomId,
        senderId: UserId,
        content: String,
        messageId: ChatMessageId? = null
    ): ChatMessage {
        val roomUuid = roomId.asUUID()
        val senderUuid = senderId.asUUID()

        chatRoomRepository.findChatRoomById(roomUuid, senderUuid)
            ?: throw ChatRoomNotFoundException()

        val sender = chatParticipantRepository.findByIdOrNull(senderUuid)
            ?: throw ChatParticipantNotFoundException(senderId)

        val targetMessageUuid = messageId?.asUUID()
        if (targetMessageUuid != null) {
            val existingMessage = chatMessageRepository.findByIdOrNull(targetMessageUuid)
            if (existingMessage != null) {
                if (existingMessage.roomId == roomUuid && existingMessage.sender.userId == senderUuid) {
                    return existingMessage.asExternalModel()
                }
                throw DuplicateMessageIdException(messageId)
            }
        }

        val savedMessage = chatMessageRepository.saveAndFlush(
            ChatMessageEntity(
                id = targetMessageUuid ?: Uuid.generateV7().toJavaUuid(),
                roomId = roomUuid,
                sender = sender,
                content = content.trim(),
            )
        )

        eventPublisher.publish(
            event = ChatEvent.NewMessage(
                roomId = roomId,
                senderId = UserId(sender.userId),
                message = savedMessage.content
            )
        )

        return savedMessage.asExternalModel()
    }

    @Transactional
    fun deleteMessage(
        messageId: ChatMessageId,
        requesterId: UserId
    ) {
        val message = chatMessageRepository.findByIdOrNull(messageId.asUUID())
            ?: throw MessageNotFoundException(messageId)

        if (requesterId != UserId(message.sender.userId)) {
            throw NotMessageSenderException()
        }

        chatMessageRepository.delete(message)

        applicationEventPublisher.publishEvent(
            InternalChatEvent.MessageDeletedEvent(
                roomId = ChatRoomId(message.roomId),
                messageId = messageId
            )
        )

        cacheManager
            .getCache(CacheNames.MESSAGES)
            ?.evict(ChatRoomId(message.roomId))
    }

    @Cacheable(
        value = [CacheNames.MESSAGES],
        key = "#roomId",
        condition = "#before == null",
        sync = true
    )
    fun getChatMessages(
        roomId: ChatRoomId,
        before: Instant?,
    ): ChatMessageSliceDto {
        val pageable = PageRequest.of(0, DEFAULT_PAGE_SIZE)
        val slice = if (before == null) {
            chatMessageRepository.findByRoomId(
                roomId = roomId.asUUID(),
                pageable = pageable
            )
        } else {
            chatMessageRepository.findByRoomIdBefore(
                roomId = roomId.asUUID(),
                before = before.truncatedTo(ChronoUnit.MILLIS),
                pageable = pageable
            )
        }

        return ChatMessageSliceDto(
            messages = slice.content.asReversed().map { it.asExternalModel().asDto() },
            hasMore = slice.hasNext()
        )
    }

    companion object {
        private const val DEFAULT_PAGE_SIZE = 20
    }
}
