package io.github.neronguyenvn.nerochat.chat.api.mapper

import io.github.neronguyenvn.nerochat.chat.domain.model.ChatMessage
import io.github.neronguyenvn.nerochat.chat.domain.model.ChatParticipant
import io.github.neronguyenvn.nerochat.chat.domain.model.ChatRoom
import io.github.neronguyenvn.nerochat.contract.chat.ChatMessageDto
import io.github.neronguyenvn.nerochat.contract.chat.ChatParticipantDto
import io.github.neronguyenvn.nerochat.contract.chat.ChatRoomDto

fun ChatMessage.asDto(): ChatMessageDto = ChatMessageDto(
    id = id.value,
    roomId = roomId.value,
    senderId = sender.userId.value,
    content = content,
    createdAt = createdAt,
)

fun ChatParticipant.asDto(): ChatParticipantDto = ChatParticipantDto(
    userId = userId.value,
    email = email,
    displayName = displayName,
    profilePictureUrl = profilePictureUrl,
)

fun ChatRoom.asDto(): ChatRoomDto = ChatRoomDto(
    id = id.value,
    type = type,
    name = name,
    creator = creator.asDto(),
    participants = participants.map { it.asDto() },
    lastMessage = lastMessage?.asDto(),
    lastActivityAt = lastActivityAt,
)
