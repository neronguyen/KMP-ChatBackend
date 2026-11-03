package io.github.neronguyenvn.nerochat.chat.domain.exception

class DirectChatRoomModificationException(
    override val message: String = "Cannot add participants to a direct chat room"
) : RuntimeException(message)
