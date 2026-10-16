package io.github.neronguyenvn.nerochat.chat.domain.exception

import io.github.neronguyenvn.nerochat.domain.type.ChatMessageId

class DuplicateMessageIdException(
    messageId: ChatMessageId
) : RuntimeException("Message with ID $messageId already exists")
