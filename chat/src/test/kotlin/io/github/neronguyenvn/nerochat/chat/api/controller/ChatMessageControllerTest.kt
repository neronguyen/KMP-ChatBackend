package io.github.neronguyenvn.nerochat.chat.api.controller

import io.github.neronguyenvn.nerochat.api.validation.InvalidUuidException
import io.github.neronguyenvn.nerochat.chat.service.ChatMessageService
import io.github.neronguyenvn.nerochat.domain.type.ChatMessageId
import io.github.neronguyenvn.nerochat.domain.type.UserId
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito.*
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import java.util.*

class ChatMessageControllerTest {

    private lateinit var chatMessageService: ChatMessageService
    private lateinit var controller: ChatMessageController

    private val userId = UserId(UUID.randomUUID())
    private val messageId = ChatMessageId(UUID.randomUUID().toString())

    @BeforeEach
    fun setUp() {
        chatMessageService = mock(ChatMessageService::class.java)
        controller = ChatMessageController(chatMessageService)

        val authentication = UsernamePasswordAuthenticationToken(userId, null, emptyList())
        SecurityContextHolder.getContext().authentication = authentication
    }

    @AfterEach
    fun tearDown() {
        SecurityContextHolder.clearContext()
    }

    @Test
    fun `deleteMessage calls chatMessageService when messageId is valid`() {
        controller.deleteMessage(messageId)

        verify(chatMessageService).deleteMessage(messageId, userId)
    }

    @Test
    fun `deleteMessage throws InvalidUuidException when messageId is invalid`() {
        val invalidMessageId = ChatMessageId("not-a-valid-uuid")

        val ex = assertThrows<InvalidUuidException> {
            controller.deleteMessage(invalidMessageId)
        }

        assertEquals("messageId", ex.parameterName)
        assertEquals("not-a-valid-uuid", ex.invalidValue)
        verifyNoInteractions(chatMessageService)
    }
}
