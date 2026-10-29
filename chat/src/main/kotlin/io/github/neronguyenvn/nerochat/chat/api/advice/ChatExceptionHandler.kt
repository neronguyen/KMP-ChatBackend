package io.github.neronguyenvn.nerochat.chat.api.advice

import io.github.neronguyenvn.nerochat.chat.domain.exception.*
import io.github.neronguyenvn.nerochat.contract.chat.error.ChatServerError
import io.github.neronguyenvn.nerochat.contract.error.ApiErrorDto
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class ChatExceptionHandler {

    @ExceptionHandler(ChatRoomNotFoundException::class)
    fun onChatRoomNotFound(e: ChatRoomNotFoundException): ResponseEntity<ApiErrorDto> =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(
            ApiErrorDto(
                error = ChatServerError.ChatRoomNotFound,
                message = e.message ?: "Chat room not found",
            )
        )

    @ExceptionHandler(ChatParticipantNotFoundException::class)
    fun onChatParticipantNotFound(e: ChatParticipantNotFoundException): ResponseEntity<ApiErrorDto> =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(
            ApiErrorDto(
                error = ChatServerError.ChatParticipantNotFound,
                message = e.message,
            )
        )

    @ExceptionHandler(MessageNotFoundException::class)
    fun onMessageNotFound(e: MessageNotFoundException): ResponseEntity<ApiErrorDto> =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(
            ApiErrorDto(
                error = ChatServerError.MessageNotFound,
                message = e.message ?: "Message not found",
            )
        )

    @ExceptionHandler(InvalidChatRoomSizeException::class)
    fun onInvalidChatRoomSize(e: InvalidChatRoomSizeException): ResponseEntity<ApiErrorDto> =
        ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
            ApiErrorDto(
                error = ChatServerError.InvalidChatRoomSize,
                message = e.message ?: "Invalid chat room size",
            )
        )

    @ExceptionHandler(ParticipantAlreadyInRoomException::class)
    fun onParticipantAlreadyInRoom(e: ParticipantAlreadyInRoomException): ResponseEntity<ApiErrorDto> =
        ResponseEntity.status(HttpStatus.CONFLICT).body(
            ApiErrorDto(
                error = ChatServerError.ParticipantAlreadyInRoom,
                message = e.message ?: "Participant is already in this chat room",
            )
        )

    @ExceptionHandler(ParticipantNotInRoomException::class)
    fun onParticipantNotInRoom(e: ParticipantNotInRoomException): ResponseEntity<ApiErrorDto> =
        ResponseEntity.status(HttpStatus.FORBIDDEN).body(
            ApiErrorDto(
                error = ChatServerError.ParticipantNotInRoom,
                message = e.message,
            )
        )

    @ExceptionHandler(NotMessageSenderException::class)
    fun onNotMessageSender(e: NotMessageSenderException): ResponseEntity<ApiErrorDto> =
        ResponseEntity.status(HttpStatus.FORBIDDEN).body(
            ApiErrorDto(
                error = ChatServerError.NotMessageSender,
                message = e.message,
            )
        )
}
