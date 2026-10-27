package io.github.neronguyenvn.nerochat.chat.domain.exception

class ParticipantNotInRoomException(
    override val message: String = "Requester is not a participant in this chat room"
) : RuntimeException(message)
