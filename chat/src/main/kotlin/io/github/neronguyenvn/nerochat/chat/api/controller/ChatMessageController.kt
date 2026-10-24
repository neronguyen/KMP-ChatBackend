package io.github.neronguyenvn.nerochat.chat.api.controller

import io.github.neronguyenvn.nerochat.api.annotation.ApiErrors
import io.github.neronguyenvn.nerochat.api.util.requesterId
import io.github.neronguyenvn.nerochat.chat.domain.exception.MessageNotFoundException
import io.github.neronguyenvn.nerochat.chat.service.ChatMessageService
import io.github.neronguyenvn.nerochat.contract.chat.error.ChatServerError
import io.github.neronguyenvn.nerochat.domain.exception.ForbiddenException
import io.github.neronguyenvn.nerochat.domain.type.ChatMessageId
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Tag(name = "Chat Messages", description = "Message history and management")
@RestController
@RequestMapping("/api/chat/messages")
class ChatMessageController(private val chatMessageService: ChatMessageService) {

    @Operation(summary = "Delete chat message", description = "Deletes a chat message sent by the requester")
    @DeleteMapping("/{messageId}")
    @ApiErrors(
        ChatServerError.Unauthorized::class,
        ChatServerError.BadRequest::class,
        ChatServerError.Forbidden::class,
        ChatServerError.MessageNotFound::class,
    )
    @Throws(MessageNotFoundException::class, ForbiddenException::class)
    fun deleteMessage(
        @PathVariable("messageId") messageId: ChatMessageId,
    ) {
        chatMessageService.deleteMessage(messageId, requesterId)
    }
}
