package io.github.neronguyenvn.nerochat.notification.service

import io.github.neronguyenvn.nerochat.domain.type.UserId
import jakarta.mail.Session
import jakarta.mail.internet.MimeMessage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.mail.javamail.JavaMailSender
import java.util.Properties
import java.util.UUID

class EmailServiceTest {

    private val javaMailSender: JavaMailSender = mock(JavaMailSender::class.java)
    private val templateService: EmailTemplateService = mock(EmailTemplateService::class.java)
    private val emailFrom = "noreply@nerochat.com"

    private lateinit var emailService: EmailService

    private val userId = UserId(UUID.randomUUID())
    private val testEmail = "test@example.com"
    private val displayName = "Alice"
    private val otpCode = "123456"

    @BeforeEach
    fun setUp() {
        emailService = EmailService(
            javaMailSender = javaMailSender,
            templateService = templateService,
            emailFrom = emailFrom
        )
        `when`(javaMailSender.createMimeMessage()).thenAnswer {
            MimeMessage(Session.getInstance(Properties()))
        }
    }

    @Test
    fun `sendVerificationEmail renders template with otpCode and dispatches email`() {
        `when`(
            templateService.processTemplate(
                any() ?: "",
                any() ?: emptyMap()
            )
        ).thenReturn("<html>verification</html>")

        emailService.sendVerificationEmail(userId, testEmail, displayName, otpCode)

        verify(templateService).processTemplate(
            templateName = "emails/account-verification",
            variables = mapOf("displayName" to displayName, "otpCode" to otpCode)
        )

        val messageCaptor = ArgumentCaptor.forClass(MimeMessage::class.java)
        verify(javaMailSender).send(messageCaptor.capture())
        val sentMessage = messageCaptor.value
        assertEquals("Verify your Nero Chat account", sentMessage.subject)
    }

    @Test
    fun `sendPasswordResetEmail renders template with otpCode and dispatches email`() {
        `when`(templateService.processTemplate(any() ?: "", any() ?: emptyMap())).thenReturn("<html>reset</html>")

        emailService.sendPasswordResetEmail(userId, testEmail, displayName, otpCode)

        verify(templateService).processTemplate(
            templateName = "emails/reset-password",
            variables = mapOf("displayName" to displayName, "otpCode" to otpCode)
        )

        val messageCaptor = ArgumentCaptor.forClass(MimeMessage::class.java)
        verify(javaMailSender).send(messageCaptor.capture())
        val sentMessage = messageCaptor.value
        assertEquals("Reset your Nero Chat password", sentMessage.subject)
    }
}
