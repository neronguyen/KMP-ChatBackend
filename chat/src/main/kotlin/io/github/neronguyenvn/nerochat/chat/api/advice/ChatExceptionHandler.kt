package io.github.neronguyenvn.nerochat.chat.api.advice

import io.github.neronguyenvn.nerochat.chat.domain.exception.ChatParticipantNotFoundException
import io.github.neronguyenvn.nerochat.chat.domain.exception.ChatRoomNotFoundException
import io.github.neronguyenvn.nerochat.chat.domain.exception.DuplicateMessageIdException
import io.github.neronguyenvn.nerochat.chat.domain.exception.InvalidChatRoomSizeException
import io.github.neronguyenvn.nerochat.chat.domain.exception.MessageNotFoundException
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class ChatExceptionHandler {

    @ExceptionHandler(
        ChatRoomNotFoundException::class,
        MessageNotFoundException::class,
        ChatParticipantNotFoundException::class,
    )
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun onForbidden(e: Exception) = mapOf(
        "code" to "NOT_FOUND",
        "message" to e.message
    )

    @ExceptionHandler(InvalidChatRoomSizeException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun onForbidden(e: InvalidChatRoomSizeException) = mapOf(
        "code" to "INVALID_CHAT_ROOM_SIZE",
        "message" to e.message
    )

    @ExceptionHandler(DuplicateMessageIdException::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    fun onDuplicateMessageId(e: DuplicateMessageIdException) = mapOf(
        "code" to "DUPLICATE_MESSAGE_ID",
        "message" to e.message
    )
}
