package io.github.neronguyenvn.nerochat.notification.service

import io.github.neronguyenvn.nerochat.domain.type.UserId
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.mail.MailException
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.mail.javamail.MimeMessageHelper
import org.springframework.stereotype.Service
import kotlin.time.Duration

@Service
class EmailService(
    private val javaMailSender: JavaMailSender,
    private val templateService: EmailTemplateService,
    @param:Value($$"${email.from}")
    private val emailFrom: String,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Renders and sends a personalized verification email containing the 6-digit [token] OTP.
     * [userId] identifies the user in logs; [email] is the delivery address.
     * Template rendering, message preparation, and sending errors propagate.
     */
    fun sendVerificationEmail(
        userId: UserId,
        email: String,
        displayName: String,
        token: String
    ) {
        logger.info("Sending verification email for user $userId")

        val htmlContent = templateService.processTemplate(
            templateName = "emails/account-verification",
            variables = mapOf(
                "displayName" to displayName,
                "otpCode" to token,
                "expiresInMinutes" to 10
            )
        )

        sendHtmlEmail(
            to = email,
            subject = "Your Nero Chat verification code",
            html = htmlContent
        )
    }

    /**
     * Renders and sends a personalized password-reset email containing the 6-digit [token] OTP.
     * Displays [expiresIn] in whole minutes and uses [userId] for logging.
     * Template rendering, message preparation, and sending errors propagate.
     */
    fun sendPasswordResetEmail(
        userId: UserId,
        email: String,
        displayName: String,
        token: String,
        expiresIn: Duration
    ) {
        logger.info("Sending password reset email for user $userId")

        val htmlContent = templateService.processTemplate(
            templateName = "emails/reset-password",
            variables = mapOf(
                "displayName" to displayName,
                "otpCode" to token,
                "expiresInMinutes" to expiresIn.inWholeMinutes
            )
        )

        sendHtmlEmail(
            to = email,
            subject = "Your Nero Chat password reset code",
            html = htmlContent
        )
    }

    /**
     * Sends [html] as a UTF-8 HTML email from the configured sender to [to].
     * [MailException] failures from sending and message preparation errors propagate.
     */
    private fun sendHtmlEmail(
        to: String,
        subject: String,
        html: String
    ) {
        val message = javaMailSender.createMimeMessage()
        MimeMessageHelper(message, true, Charsets.UTF_8.name()).apply {
            setFrom(emailFrom)
            setTo(to)
            setSubject(subject)
            setText(html, true)
        }

        try {
            javaMailSender.send(message)
        } catch (e: MailException) {
            logger.error("Could not send email", e)
            throw e
        }
    }
}
