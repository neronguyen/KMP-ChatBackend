package io.github.neronguyenvn.nerochat.chat.api.model.websocket

import io.github.neronguyenvn.nerochat.chat.api.model.ChatMessageDto
import io.github.neronguyenvn.nerochat.chat.api.model.ChatParticipantDto
import io.github.neronguyenvn.nerochat.domain.type.ChatMessageId
import io.github.neronguyenvn.nerochat.domain.type.ChatRoomId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed class OutgoingWsMessage {

    @Serializable
    @SerialName(NEW_MESSAGE)
    data class NewMessage(
        val message: ChatMessageDto
    ) : OutgoingWsMessage()

    @Serializable
    @SerialName(MESSAGE_DELETED)
    data class MessageDeleted(
        val roomId: ChatRoomId,
        val messageId: ChatMessageId
    ) : OutgoingWsMessage()

    @Serializable
    @SerialName(PARTICIPANT_JOINED)
    data class ParticipantJoined(
        val roomId: ChatRoomId,
        val newUsers: List<ChatParticipantDto>,
        val addedBy: ChatParticipantDto? = null
    ) : OutgoingWsMessage()

    @Serializable
    @SerialName(PARTICIPANT_LEFT)
    data class ParticipantLeft(
        val roomId: ChatRoomId,
        val leftUser: ChatParticipantDto,
        val kickedBy: ChatParticipantDto? = null
    ) : OutgoingWsMessage()

    @Serializable
    @SerialName(ERROR)
    data class Error(
        val code: String,
        val message: String
    ) : OutgoingWsMessage()

    companion object {
        const val NEW_MESSAGE = "new_message"
        const val MESSAGE_DELETED = "message_deleted"
        const val PARTICIPANT_JOINED = "participant_joined"
        const val PARTICIPANT_LEFT = "participant_left"
        const val ERROR = "error"
    }
}
