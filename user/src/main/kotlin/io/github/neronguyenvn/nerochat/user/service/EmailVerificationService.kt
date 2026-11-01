package io.github.neronguyenvn.nerochat.user.service

import io.github.neronguyenvn.nerochat.domain.event.UserEvent
import io.github.neronguyenvn.nerochat.domain.exception.InvalidTokenException
import io.github.neronguyenvn.nerochat.domain.util.normalizeEmail
import io.github.neronguyenvn.nerochat.infra.messagequeue.EventPublisher
import io.github.neronguyenvn.nerochat.user.domain.exception.MaxAttemptsExceededException
import io.github.neronguyenvn.nerochat.user.domain.exception.OtpExpiredException
import io.github.neronguyenvn.nerochat.user.domain.exception.UserNotFoundException
import io.github.neronguyenvn.nerochat.user.domain.model.AuthToken
import io.github.neronguyenvn.nerochat.user.domain.model.AuthTokenType
import io.github.neronguyenvn.nerochat.user.infra.database.model.AuthTokenEntity
import io.github.neronguyenvn.nerochat.user.infra.database.model.asEmailVerificationToken
import io.github.neronguyenvn.nerochat.user.infra.database.model.userId
import io.github.neronguyenvn.nerochat.user.infra.database.repository.AuthTokenRepository
import io.github.neronguyenvn.nerochat.user.infra.database.repository.UserRepository
import io.github.neronguyenvn.nerochat.user.infra.security.SecureTokenGenerator
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.temporal.ChronoUnit

@Service
class EmailVerificationService(
    private val authTokenRepository: AuthTokenRepository,
    private val userRepository: UserRepository,
    private val eventPublisher: EventPublisher,
    @param:Value($$"${email.verification.expiry-minutes:10}") private val expiryMinutes: Long = 10
) {
    /**
     * Marks the user's existing verification tokens as used and saves a new 6-digit OTP token with the
     * configured lifetime in minutes. This also applies to users whose email is already verified.
     *
     * @return the saved token and its user.
     * @throws UserNotFoundException if no user is registered with [email].
     */
    @Transactional
    fun createVerificationToken(email: String): AuthToken.EmailVerification {
        val user = userRepository.findByEmail(normalizeEmail(email))
            ?: throw UserNotFoundException.byEmail()

        authTokenRepository.invalidateEmailVerificationTokens(user)

        val expiryDate = Instant.now().plus(expiryMinutes, ChronoUnit.MINUTES)

        val token = AuthTokenEntity(
            token = SecureTokenGenerator.generateOtp(),
            expiredAt = expiryDate,
            tokenType = AuthTokenType.EmailVerification,
            user = user
        )

        return authTokenRepository.save(token).asEmailVerificationToken()
    }

    /**
     * Replaces existing verification tokens and publishes a resend request for an unverified user.
     * A new token is also created for an already verified user, but no event is published.
     * Synchronous event-publishing failures propagate to the caller.
     *
     * @throws UserNotFoundException if no user is registered with [email].
     */
    @Transactional
    fun resendVerificationEmail(email: String) {
        val token = createVerificationToken(email)
        if (token.user.isEmailVerified) {
            return
        }

        eventPublisher.publish(
            event = UserEvent.RequestResendVerification(
                userId = token.user.id,
                email = token.user.email,
                displayName = token.user.displayName,
                verificationToken = token.token
            )
        )
    }

    /**
     * Verifies the 6-digit OTP code with brute-force protection (max 5 attempts).
     * Marks the verification token as used and its user's email as verified.
     *
     * @throws InvalidTokenException if the token is missing or code is incorrect.
     * @throws OtpExpiredException if the token has expired.
     * @throws MaxAttemptsExceededException if 5 failed attempts are reached.
     */
    @Transactional
    fun verifyEmail(email: String, code: String) {
        val user = userRepository.findByEmail(normalizeEmail(email))
            ?: throw InvalidTokenException("Email verification token is invalid")

        val existing = authTokenRepository.findFirstByUserAndTokenTypeAndUsedAtIsNullOrderByCreatedAtDesc(
            user,
            AuthTokenType.EmailVerification
        ) ?: throw InvalidTokenException("Email verification token is invalid")

        if (existing.isExpired()) {
            throw OtpExpiredException("Email verification token is expired")
        }

        if (existing.attempts >= 5) {
            existing.usedAt = Instant.now()
            authTokenRepository.save(existing)
            throw MaxAttemptsExceededException("Maximum verification attempts exceeded. Please request a new code.")
        }

        if (existing.token != code) {
            existing.attempts += 1
            if (existing.attempts >= 5) {
                existing.usedAt = Instant.now()
            }
            authTokenRepository.save(existing)
            if (existing.attempts >= 5) {
                throw MaxAttemptsExceededException("Maximum verification attempts exceeded. Please request a new code.")
            }
            throw InvalidTokenException("Email verification token is invalid")
        }

        existing.usedAt = Instant.now()
        user.isEmailVerified = true

        authTokenRepository.save(existing)
        userRepository.save(user)

        eventPublisher.publish(
            UserEvent.Verified(
                userId = user.userId,
                email = user.email,
                displayName = user.displayName
            )
        )
    }
}
