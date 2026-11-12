package io.github.neronguyenvn.nerochat.notification.infra.messagequeue

import io.github.neronguyenvn.nerochat.domain.event.UserEvent
import io.github.neronguyenvn.nerochat.domain.type.UserId
import io.github.neronguyenvn.nerochat.notification.service.EmailService
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import java.util.UUID
import kotlin.time.Duration.Companion.minutes

class NotificationUserEventListenerTest {

    private val emailService: EmailService = mock(EmailService::class.java)
    private lateinit var listener: NotificationUserEventListener

    private val userId = UserId(UUID.randomUUID())
    private val email = "alice@example.com"
    private val displayName = "Alice"
    private val otpCode = "123456"

    @BeforeEach
    fun setUp() {
        listener = NotificationUserEventListener(emailService)
    }

    @Test
    fun `handles UserEvent Created by invoking sendVerificationEmail with otpCode`() {
        val event = UserEvent.Created(
            userId = userId,
            email = email,
            displayName = displayName,
            otpCode = otpCode
        )

        listener.handleUserEvent(event)

        verify(emailService).sendVerificationEmail(
            userId = userId,
            email = email,
            displayName = displayName,
            otpCode = otpCode
        )
    }

    @Test
    fun `handles UserEvent RequestResendVerification by invoking sendVerificationEmail with otpCode`() {
        val event = UserEvent.RequestResendVerification(
            userId = userId,
            email = email,
            displayName = displayName,
            otpCode = otpCode
        )

        listener.handleUserEvent(event)

        verify(emailService).sendVerificationEmail(
            userId = userId,
            email = email,
            displayName = displayName,
            otpCode = otpCode
        )
    }

    @Test
    fun `handles UserEvent RequestResetPassword by invoking sendPasswordResetEmail with otpCode`() {
        val event = UserEvent.RequestResetPassword(
            userId = userId,
            email = email,
            displayName = displayName,
            otpCode = otpCode,
            expiresIn = 3.minutes
        )

        listener.handleUserEvent(event)

        verify(emailService).sendPasswordResetEmail(
            userId = userId,
            email = email,
            displayName = displayName,
            otpCode = otpCode
        )
    }

    @Test
    fun `handles UserEvent Verified with no email interactions`() {
        val event = UserEvent.Verified(
            userId = userId,
            email = email,
            displayName = displayName
        )

        listener.handleUserEvent(event)

        verifyNoInteractions(emailService)
    }
}
