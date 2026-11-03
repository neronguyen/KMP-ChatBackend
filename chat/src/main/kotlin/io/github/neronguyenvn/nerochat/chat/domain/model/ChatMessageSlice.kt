package io.github.neronguyenvn.nerochat.chat.domain.model

/**
 * Domain slice of chat messages for paginated query results.
 */
data class ChatMessageSlice(
    val messages: List<ChatMessage>,
    val hasMore: Boolean,
)
