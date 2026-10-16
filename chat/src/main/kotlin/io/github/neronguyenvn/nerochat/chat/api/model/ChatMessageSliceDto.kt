package io.github.neronguyenvn.nerochat.chat.api.model

import kotlinx.serialization.Serializable

@Serializable
data class ChatMessageSliceDto(
    val messages: List<ChatMessageDto>,
    val hasMore: Boolean
)
