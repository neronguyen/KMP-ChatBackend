package io.github.neronguyenvn.nerochat.chat.api.controller

import io.github.neronguyenvn.nerochat.api.annotation.ApiErrors
import io.github.neronguyenvn.nerochat.api.util.requesterId
import io.github.neronguyenvn.nerochat.chat.api.mapper.asDto
import io.github.neronguyenvn.nerochat.chat.api.model.AddChatParticipantRequest
import io.github.neronguyenvn.nerochat.chat.api.model.CreateDirectChatRoomRequest
import io.github.neronguyenvn.nerochat.chat.api.model.CreateGroupChatRoomRequest
import io.github.neronguyenvn.nerochat.chat.domain.exception.ChatParticipantNotFoundException
import io.github.neronguyenvn.nerochat.chat.domain.exception.ChatRoomNotFoundException
import io.github.neronguyenvn.nerochat.chat.domain.exception.InvalidChatRoomSizeException
import io.github.neronguyenvn.nerochat.chat.domain.exception.ParticipantAlreadyInRoomException
import io.github.neronguyenvn.nerochat.chat.service.ChatRoomService
import io.github.neronguyenvn.nerochat.contract.chat.ChatMessageSliceDto
import io.github.neronguyenvn.nerochat.contract.chat.ChatRoomDto
import io.github.neronguyenvn.nerochat.contract.chat.error.ChatServerError
import io.github.neronguyenvn.nerochat.domain.exception.ForbiddenException
import io.github.neronguyenvn.nerochat.domain.type.ChatRoomId
import io.github.neronguyenvn.nerochat.domain.type.UserId
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

@RestController
@RequestMapping("/api/chat/rooms")
class ChatRoomController(private val chatRoomService: ChatRoomService) {

    @GetMapping
    @ApiErrors(ChatServerError.Unauthorized::class)
    fun getChatRooms(): List<ChatRoomDto> {
        return chatRoomService
            .findChatRoomsByUser(userId = requesterId)
            .map { it.asDto() }
    }

    @GetMapping("/{roomId}")
    @ApiErrors(
        ChatServerError.Unauthorized::class,
        ChatServerError.BadRequest::class,
        ChatServerError.ChatRoomNotFound::class,
    )
    @Throws(ChatRoomNotFoundException::class)
    fun getChatRoom(
        @PathVariable("roomId") roomId: ChatRoomId,
    ): ChatRoomDto {
        return chatRoomService
            .getChatRoomById(roomId = roomId, requesterId = requesterId)
            ?.asDto() ?: throw ChatRoomNotFoundException()
    }

    @GetMapping("/{roomId}/messages")
    @ApiErrors(
        ChatServerError.Unauthorized::class,
        ChatServerError.BadRequest::class,
        ChatServerError.Forbidden::class,
    )
    @Throws(ForbiddenException::class)
    fun getChatMessages(
        @PathVariable("roomId") roomId: ChatRoomId,
        @RequestParam("before", required = false) before: Instant? = null,
    ): ChatMessageSliceDto {
        chatRoomService.getChatRoomById(roomId, requesterId)
            ?: throw ForbiddenException()

        return chatRoomService.getChatMessages(
            roomId = roomId,
            before = before,
        )
    }

    @PostMapping("/direct")
    @ApiErrors(
        ChatServerError.Unauthorized::class,
        ChatServerError.ValidationFailed::class,
        ChatServerError.ChatParticipantNotFound::class,
        ChatServerError.InvalidChatRoomSize::class,
    )
    @Throws(ChatParticipantNotFoundException::class, InvalidChatRoomSizeException::class)
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
    @ApiErrors(
        ChatServerError.Unauthorized::class,
        ChatServerError.ValidationFailed::class,
        ChatServerError.ChatParticipantNotFound::class,
        ChatServerError.InvalidChatRoomSize::class,
    )
    @Throws(ChatParticipantNotFoundException::class, InvalidChatRoomSizeException::class)
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
    @ApiErrors(
        ChatServerError.Unauthorized::class,
        ChatServerError.BadRequest::class,
        ChatServerError.ValidationFailed::class,
        ChatServerError.Forbidden::class,
        ChatServerError.ChatRoomNotFound::class,
        ChatServerError.ChatParticipantNotFound::class,
        ChatServerError.ParticipantAlreadyInRoom::class,
    )
    @Throws(
        ChatRoomNotFoundException::class,
        ChatParticipantNotFoundException::class,
        ForbiddenException::class,
        ParticipantAlreadyInRoomException::class,
    )
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
    @ApiErrors(
        ChatServerError.Unauthorized::class,
        ChatServerError.BadRequest::class,
        ChatServerError.ChatRoomNotFound::class,
        ChatServerError.ChatParticipantNotFound::class,
    )
    @Throws(ChatRoomNotFoundException::class, ChatParticipantNotFoundException::class)
    fun leaveChatRoom(
        @PathVariable("roomId") roomId: ChatRoomId,
    ) {
        chatRoomService.removeParticipantFromChatRoom(
            roomId = roomId,
            userId = requesterId
        )
    }
}
