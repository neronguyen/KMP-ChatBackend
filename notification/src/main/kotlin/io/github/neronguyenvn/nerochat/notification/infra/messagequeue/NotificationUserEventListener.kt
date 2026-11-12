package io.github.neronguyenvn.nerochat.notification.infra.messagequeue

import io.github.neronguyenvn.nerochat.domain.event.UserEvent
import io.github.neronguyenvn.nerochat.infra.messagequeue.MessageQueue
import io.github.neronguyenvn.nerochat.notification.service.EmailService
import org.springframework.amqp.rabbit.annotation.RabbitListener
import org.springframework.stereotype.Component

@Component
class NotificationUserEventListener(
    private val emailService: EmailService
) {

    /**
     * Dispatches registration, verification-resend, and password-reset events to the email service.
     * Verified events require no email and are ignored. Email-service errors propagate to the listener container.
     */
    @RabbitListener(queues = [MessageQueue.NOTIFICATION_SERVICE_FOR_USER_EVENTS])
    fun handleUserEvent(event: UserEvent) {
        when (event) {
            is UserEvent.Created -> emailService.sendVerificationEmail(
                userId = event.userId,
                email = event.email,
                displayName = event.displayName,
                otpCode = event.otpCode
            )

            is UserEvent.RequestResendVerification -> emailService.sendVerificationEmail(
                userId = event.userId,
                email = event.email,
                displayName = event.displayName,
                otpCode = event.otpCode
            )

            is UserEvent.RequestResetPassword -> emailService.sendPasswordResetEmail(
                userId = event.userId,
                email = event.email,
                displayName = event.displayName,
                otpCode = event.otpCode
            )

            is UserEvent.Verified -> Unit
        }
    }
}
