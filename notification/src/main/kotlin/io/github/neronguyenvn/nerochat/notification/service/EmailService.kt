package io.github.neronguyenvn.nerochat.notification.service

import io.github.neronguyenvn.nerochat.domain.type.UserId
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.mail.MailException
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.mail.javamail.MimeMessageHelper
import org.springframework.stereotype.Service
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlin.time.Duration

@Service
class EmailService(
    private val javaMailSender: JavaMailSender,
    private val templateService: EmailTemplateService,
    @param:Value($$"${email.from}")
    private val emailFrom: String,
    @param:Value($$"${email.url}")
    private val baseUrl: String,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Renders and sends a personalized verification email containing [token] in the verification URL.
     * [userId] identifies the user in logs; [email] is the delivery address.
     * URL construction, template rendering, message preparation, and sending errors propagate.
     */
    // TODO: Keep authentication tokens out of request URLs.
    fun sendVerificationEmail(
        userId: UserId,
        email: String,
        displayName: String,
        token: String
    ) {
        logger.info("Sending verification email for user $userId")

        val encodedToken = URLEncoder.encode(token, StandardCharsets.UTF_8)
        val verificationUrl = "$baseUrl/api/auth/verify-email?token=$encodedToken"

        val htmlContent = templateService.processTemplate(
            templateName = "emails/account-verification",
            variables = mapOf(
                "displayName" to displayName,
                "verificationUrl" to verificationUrl
            )
        )

        sendHtmlEmail(
            to = email,
            subject = "Verify your Nero Chat account",
            html = htmlContent
        )
    }

    /**
     * Renders and sends a personalized password-reset email containing [token] in the reset URL.
     * Displays [expiresIn] in whole minutes and uses [userId] for logging.
     * URL construction, template rendering, message preparation, and sending errors propagate.
     */
    // TODO: Keep authentication tokens out of request URLs.
    fun sendPasswordResetEmail(
        userId: UserId,
        email: String,
        displayName: String,
        token: String,
        expiresIn: Duration
    ) {
        logger.info("Sending password reset email for user $userId")

        val encodedToken = URLEncoder.encode(token, StandardCharsets.UTF_8)
        val resetPasswordUrl = "$baseUrl/api/auth/reset-password?token=$encodedToken"

        val htmlContent = templateService.processTemplate(
            templateName = "emails/reset-password",
            variables = mapOf(
                "displayName" to displayName,
                "resetPasswordUrl" to resetPasswordUrl,
                "expiresInMinutes" to expiresIn.inWholeMinutes
            )
        )

        sendHtmlEmail(
            to = email,
            subject = "Reset your Nero Chat password",
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
