package io.github.neronguyenvn.nerochat.user.service

import io.github.neronguyenvn.nerochat.domain.event.UserEvent
import io.github.neronguyenvn.nerochat.domain.type.UserId
import io.github.neronguyenvn.nerochat.domain.util.normalizeEmail
import io.github.neronguyenvn.nerochat.infra.messagequeue.EventPublisher
import io.github.neronguyenvn.nerochat.user.domain.exception.SamePasswordException
import io.github.neronguyenvn.nerochat.user.domain.exception.UserNotFoundException
import io.github.neronguyenvn.nerochat.user.domain.exception.WrongPasswordException
import io.github.neronguyenvn.nerochat.user.infra.database.model.userId
import io.github.neronguyenvn.nerochat.user.infra.database.repository.RefreshTokenRepository
import io.github.neronguyenvn.nerochat.user.infra.database.repository.UserRepository
import jakarta.transaction.Transactional
import org.springframework.data.repository.findByIdOrNull
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import kotlin.time.DurationUnit
import kotlin.time.toDuration

@Service
class PasswordResetService(
    private val otpService: RedissonOtpService,
    private val userRepository: UserRepository,
    private val refreshTokenRepository: RefreshTokenRepository,
    private val passwordEncoder: PasswordEncoder,
    private val eventPublisher: EventPublisher,
) {
    /**
     * Generates a new 6-digit password-reset OTP in Redis and publishes a reset request.
     * Synchronous event-publishing failures propagate to the caller.
     *
     * @throws UserNotFoundException if no user is registered with [email].
     */
    fun requestPasswordReset(email: String) {
        val normalizedEmail = normalizeEmail(email)
        val user = userRepository.findByEmail(normalizedEmail)
            ?: throw UserNotFoundException.byEmail()

        val otpCode = otpService.generateAndStoreOtp(OtpType.RESET, normalizedEmail) ?: return

        eventPublisher.publish(
            event = UserEvent.RequestResetPassword(
                userId = user.userId,
                email = user.email,
                displayName = user.displayName,
                otpCode = otpCode,
                expiresIn = 3.toDuration(DurationUnit.MINUTES)
            )
        )
    }

    /**
     * Verifies the 6-digit OTP first, checks that [newPassword] is different from current password,
     * replaces the user's encoded password, deletes all their refresh tokens, and deletes the OTP.
     * Existing access tokens are not revoked.
     *
     * @throws io.github.neronguyenvn.nerochat.user.domain.exception.InvalidOtpException if the OTP is incorrect.
     * @throws io.github.neronguyenvn.nerochat.user.domain.exception.OtpExpiredException if the OTP is expired or missing.
     * @throws io.github.neronguyenvn.nerochat.user.domain.exception.MaxAttemptsExceededException if max attempts exceeded or locked out.
     * @throws UserNotFoundException if the user does not exist.
     * @throws SamePasswordException if [newPassword] matches the stored password.
     */
    @Transactional
    fun resetPassword(email: String, code: String, newPassword: String) {
        val normalizedEmail = normalizeEmail(email)

        // 1. Verify OTP first (prevents password oracle vulnerability)
        otpService.verifyOtp(OtpType.RESET, normalizedEmail, code)

        // 2. Fetch user
        val user = userRepository.findByEmail(normalizedEmail)
            ?: throw UserNotFoundException.byEmail()

        // 3. Semantic check: reject if new password matches existing password
        if (passwordEncoder.matches(newPassword, user.hashedPassword)) {
            throw SamePasswordException()
        }

        // 4. Invalidate all active refresh tokens for user across all sessions
        refreshTokenRepository.deleteByUserId(user.userId.asUUID())

        // 5. Update user password
        val newHashedPassword = passwordEncoder.encode(newPassword)!!
        user.hashedPassword = newHashedPassword
        userRepository.save(user)

        // 6. Delete Redis OTP keys upon successful commit
        otpService.deleteOtp(OtpType.RESET, normalizedEmail)
    }

    /**
     * Checks the current password, saves the encoded replacement, and deletes all user refresh tokens.
     * Existing access tokens are not revoked.
     *
     * @throws IllegalArgumentException if [userId] cannot be parsed as a UUID.
     * @throws UserNotFoundException if the user does not exist.
     * @throws WrongPasswordException if [oldPassword] does not match the stored password.
     * @throws SamePasswordException if [newPassword] equals [oldPassword].
     */
    @Transactional
    fun changePassword(
        userId: UserId,
        oldPassword: String,
        newPassword: String
    ) {
        val user = userRepository.findByIdOrNull(userId.asUUID())
            ?: throw UserNotFoundException.byId()

        if (!passwordEncoder.matches(oldPassword, user.hashedPassword)) {
            throw WrongPasswordException()
        }

        if (oldPassword == newPassword) {
            throw SamePasswordException()
        }

        refreshTokenRepository.deleteByUserId(user.userId.asUUID())

        val newHashedPassword = passwordEncoder.encode(newPassword)!!
        user.hashedPassword = newHashedPassword
        userRepository.save(user)
    }
}
