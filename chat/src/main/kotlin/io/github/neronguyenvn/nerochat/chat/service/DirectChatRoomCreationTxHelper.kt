package io.github.neronguyenvn.nerochat.chat.service

import io.github.neronguyenvn.nerochat.chat.domain.event.InternalChatEvent
import io.github.neronguyenvn.nerochat.chat.domain.model.ChatRoom
import io.github.neronguyenvn.nerochat.chat.infra.database.model.ChatMessageEntity
import io.github.neronguyenvn.nerochat.chat.infra.database.model.ChatParticipantEntity
import io.github.neronguyenvn.nerochat.chat.infra.database.model.ChatRoomEntity
import io.github.neronguyenvn.nerochat.chat.infra.database.model.asExternalModel
import io.github.neronguyenvn.nerochat.chat.infra.database.repository.ChatMessageRepository
import io.github.neronguyenvn.nerochat.chat.infra.database.repository.ChatRoomRepository
import io.github.neronguyenvn.nerochat.contract.chat.ChatRoomType
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@Component
class DirectChatRoomCreationTxHelper(
    private val chatRoomRepository: ChatRoomRepository,
    private val chatMessageRepository: ChatMessageRepository,
    private val applicationEventPublisher: ApplicationEventPublisher,
) {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun createDirectChatRoom(
        directPairKey: String,
        creator: ChatParticipantEntity,
        target: ChatParticipantEntity,
        message: String,
    ): ChatRoom {
        val newRoom = chatRoomRepository.save(
            ChatRoomEntity(
                type = ChatRoomType.DIRECT,
                directPairKey = directPairKey,
                creator = creator,
                participants = setOf(creator, target),
            )
        )

        val initialMessage = chatMessageRepository.save(
            ChatMessageEntity(
                roomId = newRoom.id ?: error("ChatRoomId must be generated"),
                sender = creator,
                content = message.trim(),
            )
        )

        val createdRoom = newRoom.asExternalModel(lastMessage = initialMessage.asExternalModel())

        applicationEventPublisher.publishEvent(
            InternalChatEvent.ChatRoomCreatedEvent(chatRoom = createdRoom)
        )

        return createdRoom
    }
}
