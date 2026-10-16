package io.github.neronguyenvn.nerochat.chat.api.controller

import io.github.neronguyenvn.nerochat.api.util.requesterId
import io.github.neronguyenvn.nerochat.chat.api.model.AddChatParticipantRequest
import io.github.neronguyenvn.nerochat.chat.api.model.ChatMessageSliceDto
import io.github.neronguyenvn.nerochat.chat.api.model.ChatRoomDto
import io.github.neronguyenvn.nerochat.chat.api.model.CreateDirectChatRoomRequest
import io.github.neronguyenvn.nerochat.chat.api.model.CreateGroupChatRoomRequest
import io.github.neronguyenvn.nerochat.chat.api.model.asDto
import io.github.neronguyenvn.nerochat.chat.service.ChatRoomService
import io.github.neronguyenvn.nerochat.domain.exception.ForbiddenException
import io.github.neronguyenvn.nerochat.domain.type.ChatRoomId
import io.github.neronguyenvn.nerochat.domain.type.UserId
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.util.UUID

@RestController
@RequestMapping("/api/chat/rooms")
class ChatRoomController(private val chatRoomService: ChatRoomService) {

    @GetMapping
    fun getChatRooms(): List<ChatRoomDto> {
        return chatRoomService
            .findChatRoomsByUser(userId = requesterId)
            .map { it.asDto() }
    }

    @GetMapping("/{roomId}")
    fun getChatRoom(
        @PathVariable("roomId") roomId: ChatRoomId,
    ): ChatRoomDto {
        return chatRoomService
            .getChatRoomById(roomId = roomId, requesterId = requesterId)
            ?.asDto() ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
    }

    @GetMapping("/{roomId}/messages")
    fun getChatMessages(
        @PathVariable("roomId") roomId: ChatRoomId,
        @RequestParam("before", required = false) before: Instant? = null,
        @RequestParam("beforeId", required = false) beforeId: UUID? = null,
    ): ChatMessageSliceDto {
        chatRoomService.getChatRoomById(roomId, requesterId)
            ?: throw ForbiddenException()

        return chatRoomService.getChatMessages(
            roomId = roomId,
            before = before,
            beforeId = beforeId,
        )
    }

    @PostMapping("/direct")
    fun createDirectChatRoom(
        @Valid @RequestBody body: CreateDirectChatRoomRequest,
    ): ChatRoomDto {
        return chatRoomService.createDirectChatRoom(
            creatorId = requesterId,
            targetUserId = UserId(body.targetUserId),
            message = body.message
        ).asDto()
    }

    @PostMapping("/group")
    fun createGroupChatRoom(
        @Valid @RequestBody body: CreateGroupChatRoomRequest,
    ): ChatRoomDto {
        return chatRoomService.createGroupChatRoom(
            creatorId = requesterId,
            name = body.name,
            participantIds = body.participantIds.map { UserId(it) }
        ).asDto()
    }

    @PostMapping("/{roomId}/add")
    fun addParticipants(
        @PathVariable("roomId") roomId: ChatRoomId,
        @Valid @RequestBody body: AddChatParticipantRequest,
    ): ChatRoomDto {
        return chatRoomService.addParticipantsToChatRoom(
            requesterId = requesterId,
            roomId = roomId,
            userIds = body.userIds.toSet()
        ).asDto()
    }

    @DeleteMapping("/{roomId}/leave")
    fun leaveChatRoom(
        @PathVariable("roomId") roomId: ChatRoomId,
    ) {
        chatRoomService.removeParticipantFromChatRoom(
            roomId = roomId,
            userId = requesterId
        )
    }
}
