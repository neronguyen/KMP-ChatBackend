package io.github.neronguyenvn.nerochat.chat.api.controller

import io.github.neronguyenvn.nerochat.chat.domain.exception.ChatParticipantNotFoundException
import io.github.neronguyenvn.nerochat.chat.domain.model.ChatParticipant
import io.github.neronguyenvn.nerochat.chat.service.ChatParticipantService
import io.github.neronguyenvn.nerochat.domain.type.UserId
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import java.util.UUID

class ChatParticipantControllerTest {

    private lateinit var chatParticipantService: ChatParticipantService
    private lateinit var controller: ChatParticipantController

    private val requesterUserId = UserId(UUID.randomUUID())

    @BeforeEach
    fun setUp() {
        chatParticipantService = mock(ChatParticipantService::class.java)
        controller = ChatParticipantController(chatParticipantService)

        val authentication = UsernamePasswordAuthenticationToken(requesterUserId, null, emptyList())
        SecurityContextHolder.getContext().authentication = authentication
    }

    @AfterEach
    fun tearDown() {
        SecurityContextHolder.clearContext()
    }

    @Test
    fun `getChatParticipant returns requester participant when email is null`() {
        val requesterParticipant = ChatParticipant(
            userId = requesterUserId,
            email = "requester@example.com",
            displayName = "Requester",
            profilePictureUrl = "https://example.com/pic.png"
        )
        `when`(chatParticipantService.findChatParticipantById(requesterUserId)).thenReturn(
            requesterParticipant
        )

        val result = controller.getChatParticipant(email = null)

        assertEquals(requesterUserId.value, result.userId)
        assertEquals("requester@example.com", result.email)
        assertEquals("Requester", result.displayName)
        assertEquals("https://example.com/pic.png", result.profilePictureUrl)
        verify(chatParticipantService).findChatParticipantById(requesterUserId)
    }

    @Test
    fun `getChatParticipant returns requester participant when email is blank`() {
        val requesterParticipant = ChatParticipant(
            userId = requesterUserId,
            email = "requester@example.com",
            displayName = "Requester",
            profilePictureUrl = null
        )
        `when`(chatParticipantService.findChatParticipantById(requesterUserId)).thenReturn(
            requesterParticipant
        )

        val result = controller.getChatParticipant(email = "   ")

        assertEquals(requesterUserId.value, result.userId)
        verify(chatParticipantService).findChatParticipantById(requesterUserId)
    }

    @Test
    fun `getChatParticipant throws ChatParticipantNotFoundException when requester is not found`() {
        `when`(chatParticipantService.findChatParticipantById(requesterUserId)).thenReturn(null)

        val exception = assertThrows<ChatParticipantNotFoundException> {
            controller.getChatParticipant(email = null)
        }

        assertEquals(
            "The chat participant with the ID $requesterUserId was not found.",
            exception.message
        )
        verify(chatParticipantService).findChatParticipantById(requesterUserId)
    }

    @Test
    fun `getChatParticipant returns participant when email is provided and found`() {
        val searchEmail = "target@example.com"
        val targetUserId = UserId(UUID.randomUUID())
        val targetParticipant = ChatParticipant(
            userId = targetUserId,
            email = searchEmail,
            displayName = "Target User",
            profilePictureUrl = null
        )
        `when`(chatParticipantService.findChatParticipantByEmail(searchEmail)).thenReturn(
            targetParticipant
        )

        val result = controller.getChatParticipant(email = searchEmail)

        assertEquals(targetUserId.value, result.userId)
        assertEquals(searchEmail, result.email)
        assertEquals("Target User", result.displayName)
        verify(chatParticipantService).findChatParticipantByEmail(searchEmail)
    }

    @Test
    fun `getChatParticipant throws ChatParticipantNotFoundException when email is provided and not found`() {
        val searchEmail = "nonexistent@example.com"
        `when`(chatParticipantService.findChatParticipantByEmail(searchEmail)).thenReturn(null)

        val exception = assertThrows<ChatParticipantNotFoundException> {
            controller.getChatParticipant(email = searchEmail)
        }

        assertEquals(
            "The chat participant with the email $searchEmail was not found.",
            exception.message
        )
        verify(chatParticipantService).findChatParticipantByEmail(searchEmail)
    }
}
