package io.github.neronguyenvn.nerochat.domain.event

import io.github.neronguyenvn.nerochat.domain.type.ChatRoomId
import io.github.neronguyenvn.nerochat.domain.type.UserId
import kotlinx.serialization.Serializable

@Serializable
sealed class ChatEvent : Event() {

    override val exchangeName: String = EXCHANGE_NAME

    @Serializable
    data class NewMessage(
        val roomId: ChatRoomId,
        val senderId: UserId,
        val message: String,
        override val key: String = CHAT_NEW_MESSAGE
    ) : ChatEvent()

    companion object {
        const val EXCHANGE_NAME = "chat.events"

        const val CHAT_NEW_MESSAGE = "chat.new_message"
    }
}
