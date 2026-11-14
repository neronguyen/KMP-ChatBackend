package io.github.neronguyenvn.nerochat.integration

import io.github.neronguyenvn.nerochat.domain.event.UserEvent
import io.github.neronguyenvn.nerochat.user.service.AuthService
import jakarta.mail.Session
import jakarta.mail.internet.MimeMessage
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.untilAsserted
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.atLeastOnce
import org.mockito.Mockito.reset
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import java.time.Duration
import java.util.Properties
import java.util.UUID

class UserEventAmqpIntegrationTest : AbstractContainerIntegrationTest() {

    @Autowired
    private lateinit var authService: AuthService

    @BeforeEach
    fun resetSpiesAndStubMailSender() {
        reset(javaMailSender, notificationUserEventListener, emailService)
        `when`(javaMailSender.createMimeMessage()).thenAnswer {
            MimeMessage(Session.getInstance(Properties()))
        }
    }

    @Test
    fun `register publishes UserEvent Created through RabbitMQ and triggers EmailService with 6-digit otpCode`() {
        val email = "amqp-${UUID.randomUUID()}@example.com"
        val displayName = "Alice"

        val registeredUser = authService.register(
            email = email,
            displayName = displayName,
            password = "SecurePassword123!",
        )

        await atMost Duration.ofSeconds(15) untilAsserted {
            val eventCaptor = ArgumentCaptor.forClass(UserEvent::class.java)
            verify(notificationUserEventListener, atLeastOnce()).handleUserEvent(eventCaptor.capture())

            val matchingEvent = eventCaptor.allValues
                .filterIsInstance<UserEvent.Created>()
                .firstOrNull { it.email == email }
            requireNotNull(matchingEvent) { "Expected UserEvent.Created for $email to be consumed from RabbitMQ" }

            assertEquals(registeredUser.id, matchingEvent.userId)
            assertEquals(displayName, matchingEvent.displayName)
            assertTrue(matchingEvent.otpCode.matches(Regex("\\d{6}")))

            verify(emailService).sendVerificationEmail(
                userId = registeredUser.id,
                email = email,
                displayName = displayName,
                otpCode = matchingEvent.otpCode,
            )

            val mimeCaptor = ArgumentCaptor.forClass(MimeMessage::class.java)
            verify(javaMailSender, atLeastOnce()).send(mimeCaptor.capture())
            val matchingMessage = mimeCaptor.allValues.firstOrNull { message ->
                message.allRecipients?.any { recipient -> recipient.toString() == email } == true
            }
            requireNotNull(matchingMessage) { "Expected MimeMessage dispatched to $email" }
            assertEquals("Verify your Nero Chat account", matchingMessage.subject)
        }
    }
}
