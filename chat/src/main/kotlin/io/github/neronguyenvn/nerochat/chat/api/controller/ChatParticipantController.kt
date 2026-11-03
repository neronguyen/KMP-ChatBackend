package io.github.neronguyenvn.nerochat.chat.api.controller

import io.github.neronguyenvn.nerochat.api.annotation.ApiErrors
import io.github.neronguyenvn.nerochat.api.util.requesterId
import io.github.neronguyenvn.nerochat.chat.api.mapper.asDto
import io.github.neronguyenvn.nerochat.chat.domain.exception.ChatParticipantNotFoundException
import io.github.neronguyenvn.nerochat.chat.service.ChatParticipantService
import io.github.neronguyenvn.nerochat.contract.chat.ChatParticipantDto
import io.github.neronguyenvn.nerochat.contract.chat.error.ChatServerError
import io.github.neronguyenvn.nerochat.contract.error.CommonServerError
import io.github.neronguyenvn.nerochat.contract.validation.chat.ChatValidator
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

@Tag(name = "Chat Participants", description = "Participant lookup")
@RestController
@RequestMapping("/api/chat/participants")
class ChatParticipantController(private val chatParticipantService: ChatParticipantService) {

    @Operation(summary = "Get chat participant", description = "Looks up a chat participant by email or returns the requester")
    @GetMapping
    @ApiErrors(
        CommonServerError.BadRequest::class,
        CommonServerError.Unauthorized::class,
        ChatServerError.ChatParticipantNotFound::class,
    )
    @Throws(ChatParticipantNotFoundException::class)
    fun getChatParticipant(
        @RequestParam(required = false) email: String?,
    ): ChatParticipantDto {
        val participant = if (email.isNullOrBlank()) {
            chatParticipantService.findChatParticipantById(userId = requesterId)
                ?: throw ChatParticipantNotFoundException(requesterId)
        } else {
            val validationError = ChatValidator.validateSearchEmail(email)
            if (validationError != null) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, validationError.message)
            }
            chatParticipantService.findChatParticipantByEmail(email = email)
                ?: throw ChatParticipantNotFoundException.byEmail(email)
        }

        return participant.asDto()
    }
}
