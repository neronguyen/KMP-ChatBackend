package io.github.neronguyenvn.nerochat.chat.api.advice

import io.github.neronguyenvn.nerochat.chat.domain.exception.*
import io.github.neronguyenvn.nerochat.contract.chat.error.ChatServerError
import io.github.neronguyenvn.nerochat.domain.type.ChatMessageId
import io.github.neronguyenvn.nerochat.domain.type.UserId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import java.util.*

class ChatExceptionHandlerTest {

    private val handler = ChatExceptionHandler()

    @Test
    fun `onChatRoomNotFound returns 404 and CHAT_ROOM_NOT_FOUND code`() {
        val ex = ChatRoomNotFoundException()
        val response = handler.onChatRoomNotFound(ex)

        assertEquals(HttpStatus.NOT_FOUND, response.statusCode)
        assertNotNull(response.body)
        assertEquals(ChatServerError.ChatRoomNotFound.code, response.body?.code)
        assertEquals("Chat room not found", response.body?.message)
    }

    @Test
    fun `onChatParticipantNotFound returns 404 and CHAT_PARTICIPANT_NOT_FOUND code`() {
        val userId = UserId(UUID.randomUUID())
        val ex = ChatParticipantNotFoundException(userId)
        val response = handler.onChatParticipantNotFound(ex)

        assertEquals(HttpStatus.NOT_FOUND, response.statusCode)
        assertNotNull(response.body)
        assertEquals(ChatServerError.ChatParticipantNotFound.code, response.body?.code)
        assertEquals(ex.message, response.body?.message)
    }

    @Test
    fun `onMessageNotFound returns 404 and MESSAGE_NOT_FOUND code`() {
        val messageId = ChatMessageId(UUID.randomUUID().toString())
        val ex = MessageNotFoundException(messageId)
        val response = handler.onMessageNotFound(ex)

        assertEquals(HttpStatus.NOT_FOUND, response.statusCode)
        assertNotNull(response.body)
        assertEquals(ChatServerError.MessageNotFound.code, response.body?.code)
        assertEquals("Message with ID $messageId not found", response.body?.message)
    }

    @Test
    fun `onInvalidChatRoomSize returns 400 and INVALID_CHAT_ROOM_SIZE code`() {
        val ex = InvalidChatRoomSizeException("Room requires at least 2 participants")
        val response = handler.onInvalidChatRoomSize(ex)

        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
        assertNotNull(response.body)
        assertEquals(ChatServerError.InvalidChatRoomSize.code, response.body?.code)
        assertEquals("Room requires at least 2 participants", response.body?.message)
    }

    @Test
    fun `onParticipantAlreadyInRoom returns 409 and PARTICIPANT_ALREADY_IN_ROOM code`() {
        val userId = UserId(UUID.randomUUID())
        val ex = ParticipantAlreadyInRoomException(userId)
        val response = handler.onParticipantAlreadyInRoom(ex)

        assertEquals(HttpStatus.CONFLICT, response.statusCode)
        assertNotNull(response.body)
        assertEquals(ChatServerError.ParticipantAlreadyInRoom.code, response.body?.code)
        assertEquals(ex.message, response.body?.message)
    }
}
