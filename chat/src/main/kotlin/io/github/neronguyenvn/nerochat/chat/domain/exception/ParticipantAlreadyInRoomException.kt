package io.github.neronguyenvn.nerochat.chat.domain.exception

import io.github.neronguyenvn.nerochat.domain.type.UserId

class ParticipantAlreadyInRoomException(
    userId: UserId
) : RuntimeException("Participant with ID $userId is already in this chat room")
