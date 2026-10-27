package io.github.neronguyenvn.nerochat.chat.api.controller

import io.github.neronguyenvn.nerochat.api.annotation.ApiErrors
import io.github.neronguyenvn.nerochat.api.util.requesterId
import io.github.neronguyenvn.nerochat.api.validation.requireValidUuid
import io.github.neronguyenvn.nerochat.chat.domain.exception.MessageNotFoundException
import io.github.neronguyenvn.nerochat.chat.domain.exception.NotMessageSenderException
import io.github.neronguyenvn.nerochat.chat.service.ChatMessageService
import io.github.neronguyenvn.nerochat.contract.chat.error.ChatServerError
import io.github.neronguyenvn.nerochat.contract.error.CommonServerError
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
        CommonServerError.Unauthorized::class,
        CommonServerError.BadRequest::class,
        ChatServerError.NotMessageSender::class,
        ChatServerError.MessageNotFound::class,
    )
    @Throws(MessageNotFoundException::class, NotMessageSenderException::class)
    fun deleteMessage(
        @PathVariable("messageId") messageId: ChatMessageId,
    ) {
        messageId.requireValidUuid()
        chatMessageService.deleteMessage(messageId, requesterId)
    }
}
