package io.github.neronguyenvn.nerochat.chat.domain.model

import io.github.neronguyenvn.nerochat.domain.type.ChatMessageId
import io.github.neronguyenvn.nerochat.domain.type.ChatRoomId
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
data class ChatMessage(
    val id: ChatMessageId,
    val roomId: ChatRoomId,
    val sender: ChatParticipant,
    val content: String,
    val createdAt: Instant
)
