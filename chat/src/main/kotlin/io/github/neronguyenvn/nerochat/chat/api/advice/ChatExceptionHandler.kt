package io.github.neronguyenvn.nerochat.chat.api.advice

import io.github.neronguyenvn.nerochat.chat.domain.exception.*
import io.github.neronguyenvn.nerochat.contract.chat.error.ChatServerError
import io.github.neronguyenvn.nerochat.contract.error.ApiErrorResponse
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
    fun onChatRoomNotFound(e: ChatRoomNotFoundException): ResponseEntity<ApiErrorResponse> =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(
            ApiErrorResponse(
                code = ChatServerError.ChatRoomNotFound.code,
                message = e.message ?: "Chat room not found",
            )
        )

    @ExceptionHandler(ChatParticipantNotFoundException::class)
    fun onChatParticipantNotFound(e: ChatParticipantNotFoundException): ResponseEntity<ApiErrorResponse> =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(
            ApiErrorResponse(
                code = ChatServerError.ChatParticipantNotFound.code,
                message = e.message,
            )
        )

    @ExceptionHandler(MessageNotFoundException::class)
    fun onMessageNotFound(e: MessageNotFoundException): ResponseEntity<ApiErrorResponse> =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(
            ApiErrorResponse(
                code = ChatServerError.MessageNotFound.code,
                message = e.message ?: "Message not found",
            )
        )

    @ExceptionHandler(InvalidChatRoomSizeException::class)
    fun onInvalidChatRoomSize(e: InvalidChatRoomSizeException): ResponseEntity<ApiErrorResponse> =
        ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
            ApiErrorResponse(
                code = ChatServerError.InvalidChatRoomSize.code,
                message = e.message ?: "Invalid chat room size",
            )
        )

    @ExceptionHandler(ParticipantAlreadyInRoomException::class)
    fun onParticipantAlreadyInRoom(e: ParticipantAlreadyInRoomException): ResponseEntity<ApiErrorResponse> =
        ResponseEntity.status(HttpStatus.CONFLICT).body(
            ApiErrorResponse(
                code = ChatServerError.ParticipantAlreadyInRoom.code,
                message = e.message ?: "Participant is already in this chat room",
            )
        )
}
