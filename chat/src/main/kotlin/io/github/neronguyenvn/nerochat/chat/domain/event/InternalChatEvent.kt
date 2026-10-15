package io.github.neronguyenvn.nerochat.chat.domain.event

import io.github.neronguyenvn.nerochat.chat.domain.model.ChatParticipant
import io.github.neronguyenvn.nerochat.chat.domain.model.ChatRoom
import io.github.neronguyenvn.nerochat.domain.type.ChatMessageId
import io.github.neronguyenvn.nerochat.domain.type.ChatRoomId

internal sealed class InternalChatEvent {

    data class ChatRoomCreatedEvent(
        val chatRoom: ChatRoom
    ) : InternalChatEvent()

    data class ChatParticipantJoinedEvent(
        val roomId: ChatRoomId,
        val newUsers: Set<ChatParticipant>
    ) : InternalChatEvent()

    data class ChatParticipantLeftEvent(
        val roomId: ChatRoomId,
        val leftUser: ChatParticipant
    ) : InternalChatEvent()

    data class MessageDeletedEvent(
        val roomId: ChatRoomId,
        val messageId: ChatMessageId,
    ) : InternalChatEvent()
}
