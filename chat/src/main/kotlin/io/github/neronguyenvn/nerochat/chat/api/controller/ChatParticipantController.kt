package io.github.neronguyenvn.nerochat.chat.api.controller

import io.github.neronguyenvn.nerochat.api.util.requesterId
import io.github.neronguyenvn.nerochat.chat.api.mapper.asDto
import io.github.neronguyenvn.nerochat.chat.domain.exception.ChatParticipantNotFoundException
import io.github.neronguyenvn.nerochat.chat.service.ChatParticipantService
import io.github.neronguyenvn.nerochat.contract.chat.ChatParticipantDto
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/chat/participants")
class ChatParticipantController(private val chatParticipantService: ChatParticipantService) {

    @GetMapping
    fun getChatParticipant(
        @RequestParam(required = false) email: String?,
    ): ChatParticipantDto {
        val participant = if (email.isNullOrBlank()) {
            chatParticipantService.findChatParticipantById(userId = requesterId)
                ?: throw ChatParticipantNotFoundException(requesterId)
        } else {
            chatParticipantService.findChatParticipantByEmail(email = email)
                ?: throw ChatParticipantNotFoundException.byEmail(email)
        }

        return participant.asDto()
    }
}
