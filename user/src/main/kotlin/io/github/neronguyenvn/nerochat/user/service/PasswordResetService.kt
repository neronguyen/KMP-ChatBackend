package io.github.neronguyenvn.nerochat.user.service

import io.github.neronguyenvn.nerochat.domain.event.UserEvent
import io.github.neronguyenvn.nerochat.domain.exception.InvalidTokenException
import io.github.neronguyenvn.nerochat.domain.type.UserId
import io.github.neronguyenvn.nerochat.domain.util.normalizeEmail
import io.github.neronguyenvn.nerochat.infra.messagequeue.EventPublisher
import io.github.neronguyenvn.nerochat.user.domain.exception.SamePasswordException
import io.github.neronguyenvn.nerochat.user.domain.exception.UserNotFoundException
import io.github.neronguyenvn.nerochat.user.domain.exception.WrongPasswordException
import io.github.neronguyenvn.nerochat.user.domain.model.AuthTokenType
import io.github.neronguyenvn.nerochat.user.infra.database.model.AuthTokenEntity
import io.github.neronguyenvn.nerochat.user.infra.database.model.userId
import io.github.neronguyenvn.nerochat.user.infra.database.repository.AuthTokenRepository
import io.github.neronguyenvn.nerochat.user.infra.database.repository.RefreshTokenRepository
import io.github.neronguyenvn.nerochat.user.infra.database.repository.UserRepository
import io.github.neronguyenvn.nerochat.user.infra.security.SecureTokenGenerator
import jakarta.transaction.Transactional
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.repository.findByIdOrNull
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.time.DurationUnit
import kotlin.time.toDuration

@Service
class PasswordResetService(
    private val authTokenRepository: AuthTokenRepository,
    private val userRepository: UserRepository,
    private val refreshTokenRepository: RefreshTokenRepository,
    private val passwordEncoder: PasswordEncoder,
    private val eventPublisher: EventPublisher,
    @param:Value($$"${email.password-reset.expiry-minutes}") private val expiryMinutes: Long
) {
    /**
     * Invalidates earlier password-reset tokens, saves a new expiring token, and attempts to publish a reset request.
     * Synchronous event-publishing failures propagate to the caller.
     *
     * @throws UserNotFoundException if no user is registered with [email].
     */
    @Transactional
    fun requestPasswordReset(email: String){
        val user = userRepository.findByEmail(normalizeEmail(email))
            ?: throw UserNotFoundException()

        authTokenRepository.invalidatePasswordResetTokens(user)

        val expiryDate = Instant.now().plus(expiryMinutes, ChronoUnit.MINUTES)
        // TODO: Simplify token param
        val token = AuthTokenEntity(
            token = SecureTokenGenerator.generate(),
            expiredAt = expiryDate,
            tokenType = AuthTokenType.PasswordReset,
            user = user
        )
        authTokenRepository.save(token)

        eventPublisher.publish(
            event = UserEvent.RequestResetPassword(
                userId = user.userId,
                email = user.email,
                displayName = user.displayName,
                passwordResetToken = token.token,
                expiresIn = expiryMinutes.toDuration(DurationUnit.MINUTES)
            )
        )
    }

    /**
     * Replaces the user's encoded password, deletes all their refresh tokens, and consumes [token].
     * Existing access tokens are not revoked.
     *
     * @throws InvalidTokenException if the token is missing, has the wrong type, is used, or is expired.
     * @throws SamePasswordException if [newPassword] matches the stored password.
     */
    @Transactional
    fun resetPassword(token: String, newPassword: String) {
        val existingToken = authTokenRepository.findByIdOrNull(token)
            ?: throw InvalidTokenException("Password reset token is invalid")

        if (existingToken.tokenType != AuthTokenType.PasswordReset) {
            throw InvalidTokenException("Password reset token is invalid")
        }

        if (existingToken.isUsed()) {
            throw InvalidTokenException("Password reset token is already used")
        }

        if (existingToken.isExpired()) {
            throw InvalidTokenException("Password reset token is expired")
        }

        val user = existingToken.user

        if (passwordEncoder.matches(newPassword, user.hashedPassword)) {
            throw SamePasswordException()
        }

        refreshTokenRepository.deleteByUserId(user.userId.asUUID())

        val newHashedPassword = passwordEncoder.encode(newPassword)!!
        user.hashedPassword = newHashedPassword
        userRepository.save(user)

        existingToken.usedAt = Instant.now()
        authTokenRepository.save(existingToken)
    }

    /**
     * Checks the current password, saves the encoded replacement, and deletes all user refresh tokens.
     * Existing access tokens are not revoked.
     *
     * @throws IllegalArgumentException if [userId] cannot be parsed as a UUID.
     * @throws IllegalStateException if the user does not exist.
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
            ?: throw UserNotFoundException()

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
