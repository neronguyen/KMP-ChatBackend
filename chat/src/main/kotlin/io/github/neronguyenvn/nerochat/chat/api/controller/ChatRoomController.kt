package io.github.neronguyenvn.nerochat.chat.api.controller

import io.github.neronguyenvn.nerochat.api.annotation.ApiErrors
import io.github.neronguyenvn.nerochat.api.util.requesterId
import io.github.neronguyenvn.nerochat.api.validation.requireValidUuid
import io.github.neronguyenvn.nerochat.chat.api.mapper.asDto
import io.github.neronguyenvn.nerochat.chat.domain.exception.ChatParticipantNotFoundException
import io.github.neronguyenvn.nerochat.chat.domain.exception.ChatRoomNotFoundException
import io.github.neronguyenvn.nerochat.chat.domain.exception.InvalidChatRoomSizeException
import io.github.neronguyenvn.nerochat.chat.domain.exception.ParticipantAlreadyInRoomException
import io.github.neronguyenvn.nerochat.chat.domain.exception.ParticipantNotInRoomException
import io.github.neronguyenvn.nerochat.chat.service.ChatMessageService
import io.github.neronguyenvn.nerochat.chat.service.ChatRoomService
import io.github.neronguyenvn.nerochat.contract.chat.*
import io.github.neronguyenvn.nerochat.contract.chat.error.ChatServerError
import io.github.neronguyenvn.nerochat.contract.error.CommonServerError
import io.github.neronguyenvn.nerochat.domain.type.ChatRoomId
import io.github.neronguyenvn.nerochat.domain.type.UserId
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.*
import java.time.Instant

@Tag(name = "Chat Rooms", description = "Chat room management")
@RestController
@RequestMapping("/api/chat/rooms")
class ChatRoomController(
    private val chatRoomService: ChatRoomService,
    private val chatMessageService: ChatMessageService,
) {

    @Operation(summary = "Get user chat rooms", description = "Retrieves all chat rooms the requester is part of")
    @GetMapping
    @ApiErrors(CommonServerError.Unauthorized::class)
    fun getChatRooms(): List<ChatRoomDto> {
        return chatRoomService
            .findChatRoomsByUser(userId = requesterId)
            .map { it.asDto() }
    }

    @Operation(summary = "Get chat room by ID", description = "Retrieves details of a specific chat room")
    @GetMapping("/{roomId}")
    @ApiErrors(
        CommonServerError.Unauthorized::class,
        CommonServerError.BadRequest::class,
        ChatServerError.ChatRoomNotFound::class,
    )
    @Throws(ChatRoomNotFoundException::class)
    fun getChatRoom(
        @PathVariable("roomId") roomId: ChatRoomId,
    ): ChatRoomDto {
        roomId.requireValidUuid()
        return chatRoomService
            .getChatRoomById(roomId = roomId, requesterId = requesterId)
            ?.asDto() ?: throw ChatRoomNotFoundException()
    }

    @Operation(summary = "Get chat room messages", description = "Retrieves paginated messages for a chat room")
    @GetMapping("/{roomId}/messages")
    @ApiErrors(
        CommonServerError.Unauthorized::class,
        CommonServerError.BadRequest::class,
        ChatServerError.ChatRoomNotFound::class,
        ChatServerError.ParticipantNotInRoom::class,
    )
    @Throws(ParticipantNotInRoomException::class, ChatRoomNotFoundException::class)
    fun getChatMessages(
        @PathVariable("roomId") roomId: ChatRoomId,
        @RequestParam("before", required = false) before: Instant? = null,
    ): ChatMessageSliceDto {
        roomId.requireValidUuid()
        if (!chatRoomService.existsById(roomId)) {
            throw ChatRoomNotFoundException()
        }
        chatRoomService.getChatRoomById(roomId, requesterId)
            ?: throw ParticipantNotInRoomException()

        return chatMessageService.getChatMessages(
            roomId = roomId,
            before = before,
        )
    }

    @Operation(summary = "Create direct chat room", description = "Creates a 1-on-1 direct chat room")
    @PostMapping("/direct")
    @ApiErrors(
        CommonServerError.Unauthorized::class,
        CommonServerError.ValidationFailed::class,
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

    @Operation(summary = "Create group chat room", description = "Creates a group chat room with multiple participants")
    @PostMapping("/group")
    @ApiErrors(
        CommonServerError.Unauthorized::class,
        CommonServerError.ValidationFailed::class,
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

    @Operation(summary = "Add participants to group chat room", description = "Adds new participants to an existing group chat room")
    @PostMapping("/{roomId}/add")
    @ApiErrors(
        CommonServerError.Unauthorized::class,
        CommonServerError.BadRequest::class,
        CommonServerError.ValidationFailed::class,
        ChatServerError.ChatRoomNotFound::class,
        ChatServerError.ChatParticipantNotFound::class,
        ChatServerError.ParticipantAlreadyInRoom::class,
        ChatServerError.ParticipantNotInRoom::class,
    )
    @Throws(
        ChatRoomNotFoundException::class,
        ChatParticipantNotFoundException::class,
        ParticipantNotInRoomException::class,
        ParticipantAlreadyInRoomException::class,
    )
    fun addParticipants(
        @PathVariable("roomId") roomId: ChatRoomId,
        @Valid @RequestBody body: AddChatParticipantRequest,
    ): ChatRoomDto {
        roomId.requireValidUuid()
        return chatRoomService.addParticipantsToChatRoom(
            requesterId = requesterId,
            roomId = roomId,
            userIds = body.userIds.map(::UserId).toSet()
        ).asDto()
    }

    @Operation(summary = "Leave chat room", description = "Leaves a chat room")
    @DeleteMapping("/{roomId}/leave")
    @ApiErrors(
        CommonServerError.Unauthorized::class,
        CommonServerError.BadRequest::class,
        ChatServerError.ChatRoomNotFound::class,
        ChatServerError.ChatParticipantNotFound::class,
    )
    @Throws(ChatRoomNotFoundException::class, ChatParticipantNotFoundException::class)
    fun leaveChatRoom(
        @PathVariable("roomId") roomId: ChatRoomId,
    ) {
        roomId.requireValidUuid()
        chatRoomService.removeParticipantFromChatRoom(
            roomId = roomId,
            userId = requesterId
        )
    }
}
