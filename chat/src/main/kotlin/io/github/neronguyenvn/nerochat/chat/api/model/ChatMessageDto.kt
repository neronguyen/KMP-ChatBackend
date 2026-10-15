package io.github.neronguyenvn.nerochat.chat.api.model

import io.github.neronguyenvn.nerochat.chat.domain.model.ChatMessage
import kotlinx.serialization.Serializable
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
@Serializable
data class ChatMessageDto(
    val id: String,
    val roomId: String,
    val senderId: String,
    val content: String,
    val createdAt: Instant,
)

@OptIn(ExperimentalTime::class)
fun ChatMessage.asDto(): ChatMessageDto = ChatMessageDto(
    id = id.value,
    roomId = roomId.value,
    senderId = sender.userId.value,
    content = content,
    createdAt = createdAt,
)
