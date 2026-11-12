package io.github.neronguyenvn.nerochat.user.service

import io.github.neronguyenvn.nerochat.domain.event.UserEvent
import io.github.neronguyenvn.nerochat.domain.util.normalizeEmail
import io.github.neronguyenvn.nerochat.infra.messagequeue.EventPublisher
import io.github.neronguyenvn.nerochat.user.domain.exception.UserNotFoundException
import io.github.neronguyenvn.nerochat.user.infra.database.model.userId
import io.github.neronguyenvn.nerochat.user.infra.database.repository.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class EmailVerificationService(
    private val otpService: RedissonOtpService,
    private val userRepository: UserRepository,
    private val eventPublisher: EventPublisher,
) {
    /**
     * Generates a 6-digit verification OTP and stores it in Redis.
     * Used during registration.
     */
    fun createVerificationOtp(email: String): String {
        val normalizedEmail = normalizeEmail(email)
        return otpService.generateAndStoreOtp(OtpType.VERIFY, normalizedEmail)
            ?: error("Cannot generate verification OTP: account is locked out")
    }

    /**
     * Generates a new verification OTP and publishes a resend request for an unverified user.
     * If user is already verified or under lockout, no event is published (anti-enumeration).
     */
    fun resendVerificationEmail(email: String) {
        val normalizedEmail = normalizeEmail(email)
        val user = userRepository.findByEmail(normalizedEmail) ?: return
        if (user.isEmailVerified) {
            return
        }

        val otpCode = otpService.generateAndStoreOtp(OtpType.VERIFY, normalizedEmail) ?: return
        eventPublisher.publish(
            event = UserEvent.RequestResendVerification(
                userId = user.userId,
                email = user.email,
                displayName = user.displayName,
                otpCode = otpCode
            )
        )
    }

    /**
     * Verifies the 6-digit OTP in Redis and marks the user's email as verified in PostgreSQL.
     *
     * @throws io.github.neronguyenvn.nerochat.user.domain.exception.InvalidOtpException on invalid code
     * @throws io.github.neronguyenvn.nerochat.user.domain.exception.OtpExpiredException on expired code
     * @throws io.github.neronguyenvn.nerochat.user.domain.exception.MaxAttemptsExceededException on max attempts or lockout
     * @throws UserNotFoundException if no user is found with [email]
     */
    @Transactional
    fun verifyEmail(email: String, code: String) {
        val normalizedEmail = normalizeEmail(email)
        otpService.verifyOtp(OtpType.VERIFY, normalizedEmail, code)

        val user = userRepository.findByEmail(normalizedEmail)
            ?: throw UserNotFoundException.byEmail()

        user.isEmailVerified = true
        userRepository.save(user)

        eventPublisher.publish(
            UserEvent.Verified(
                userId = user.userId,
                email = user.email,
                displayName = user.displayName
            )
        )

        otpService.deleteOtp(OtpType.VERIFY, normalizedEmail)
    }
}
