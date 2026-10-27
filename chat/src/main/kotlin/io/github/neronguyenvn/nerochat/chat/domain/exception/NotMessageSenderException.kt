package io.github.neronguyenvn.nerochat.chat.domain.exception

class NotMessageSenderException(
    override val message: String = "Only the message sender can delete this message"
) : RuntimeException(message)
