package io.github.neronguyenvn.nerochat.user.service

import io.github.neronguyenvn.nerochat.domain.event.UserEvent
import io.github.neronguyenvn.nerochat.domain.exception.InvalidTokenException
import io.github.neronguyenvn.nerochat.domain.type.UserId
import io.github.neronguyenvn.nerochat.domain.util.normalizeEmail
import io.github.neronguyenvn.nerochat.infra.messagequeue.EventPublisher
import io.github.neronguyenvn.nerochat.service.JwtService
import io.github.neronguyenvn.nerochat.user.domain.exception.EmailNotVerifiedException
import io.github.neronguyenvn.nerochat.user.domain.exception.InvalidCredentialsException
import io.github.neronguyenvn.nerochat.user.domain.exception.UserAlreadyExistsException
import io.github.neronguyenvn.nerochat.user.domain.exception.UserNotFoundException
import io.github.neronguyenvn.nerochat.user.domain.model.AuthenticatedUser
import io.github.neronguyenvn.nerochat.user.domain.model.User
import io.github.neronguyenvn.nerochat.user.infra.database.model.RefreshTokenEntity
import io.github.neronguyenvn.nerochat.user.infra.database.model.UserEntity
import io.github.neronguyenvn.nerochat.user.infra.database.model.asExternalModel
import io.github.neronguyenvn.nerochat.user.infra.database.model.userId
import io.github.neronguyenvn.nerochat.user.infra.database.repository.RefreshTokenRepository
import io.github.neronguyenvn.nerochat.user.infra.database.repository.UserRepository
import jakarta.transaction.Transactional
import org.springframework.data.repository.findByIdOrNull
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import java.security.MessageDigest
import java.time.Instant
import kotlin.io.encoding.Base64

@Service
class AuthService(
    private val userRepository: UserRepository,
    private val refreshTokenRepository: RefreshTokenRepository,
    private val emailVerificationService: EmailVerificationService,
    private val passwordEncoder: PasswordEncoder,
    private val jwtService: JwtService,
    private val eventPublisher: EventPublisher
) {
    /**
     * Creates a user with an encoded password and a verification token, then attempts to publish a creation event.
     * Synchronous event-publishing failures propagate to the caller.
     *
     * @return the newly persisted user.
     * @throws UserAlreadyExistsException if [email] is already registered.
     */
    @Transactional
    fun register(
        email: String,
        displayName: String,
        password: String,
    ): User {
        val normalizedEmail = normalizeEmail(email)
        val existing = userRepository.findByEmail(normalizedEmail)
        if (existing != null) {
            throw UserAlreadyExistsException()
        }

        val saved = userRepository.saveAndFlush(
            UserEntity(
                email = normalizedEmail,
                displayName = displayName,
                hashedPassword = passwordEncoder.encode(password)!!,
            )
        )

        val token = emailVerificationService.createVerificationToken(normalizedEmail)
        eventPublisher.publish(
            event = UserEvent.Created(
                userId = saved.userId,
                email = saved.email,
                displayName = displayName,
                verificationToken = token.token
            )
        )

        return saved.asExternalModel()
    }

    /**
     * Authenticates a verified user and returns their profile, access token, and refresh token.
     * Persists a hash of the refresh token for subsequent refresh and logout requests.
     *
     * @throws InvalidCredentialsException if the email is unknown or the password does not match.
     * @throws EmailNotVerifiedException if the password matches but the email is unverified.
     */
    fun login(
        email: String,
        password: String,
    ): AuthenticatedUser {
        val existing = userRepository.findByEmail(normalizeEmail(email)) ?: throw InvalidCredentialsException()

        val matches = passwordEncoder.matches(
            password,
            existing.hashedPassword
        )

        if (!matches) throw InvalidCredentialsException()
        if (!existing.isEmailVerified) throw EmailNotVerifiedException()

        val userId = existing.userId
        val accessToken = jwtService.generateAccessToken(userId)
        val refreshToken = jwtService.generateRefreshToken(userId)

        saveRefreshToken(userId, refreshToken)

        return AuthenticatedUser(
            user = existing.asExternalModel(),
            accessToken = accessToken,
            refreshToken = refreshToken
        )
    }

    /**
     * Returns the user and newly generated tokens after replacing the stored refresh-token hash.
     * Requires a valid refresh JWT and a matching stored hash; stored expiration is not checked.
     *
     * @throws InvalidTokenException if JWT validation fails or the stored token is absent.
     * @throws UserNotFoundException if the token's user no longer exists.
     * @throws IllegalArgumentException if the token's subject cannot be parsed as a UUID.
     */
    @Transactional
    fun refreshToken(refreshToken: String): AuthenticatedUser {
        if (!jwtService.validateRefreshToken(refreshToken)) {
            throw InvalidTokenException("Invalid refresh token")
        }

        val userId = jwtService.getUserIdFromToken(refreshToken)
        val hashedToken = hashToken(refreshToken)

        val user = userRepository.findByIdOrNull(userId.asUUID())
            ?: throw UserNotFoundException.byId()

        val deleted = refreshTokenRepository.deleteByUserIdAndHashedToken(userId.asUUID(), hashedToken)
        if (deleted == 0L) {
            throw InvalidTokenException("Invalid refresh token")
        }

        val newAccessToken = jwtService.generateAccessToken(userId)
        val newRefreshToken = jwtService.generateRefreshToken(userId)

        saveRefreshToken(userId, newRefreshToken)

        return AuthenticatedUser(
            user = user.asExternalModel(),
            accessToken = newAccessToken,
            refreshToken = newRefreshToken
        )
    }

    /**
     * Deletes the matching stored refresh token, if present. Existing access tokens are not revoked.
     *
     * @throws InvalidTokenException if the JWT is not a valid refresh token.
     * @throws IllegalArgumentException if the token's subject cannot be parsed as a UUID.
     */
    @Transactional
    fun logout(refreshToken: String) {
        if (!jwtService.validateRefreshToken(refreshToken)) {
            throw InvalidTokenException("Invalid refresh token")
        }

        val userId = jwtService.getUserIdFromToken(refreshToken)
        val hashToken = hashToken(refreshToken)
        refreshTokenRepository.deleteByUserIdAndHashedToken(userId.asUUID(), hashToken)
    }

    /**
     * Persists the token's hash with a 30-day expiration measured from now.
     *
     * @throws IllegalArgumentException if [userId] cannot be parsed as a UUID.
     */
    private fun saveRefreshToken(userId: UserId, refreshToken: String) {
        val hashedToken = hashToken(refreshToken)
        val expiryMillis = jwtService.refreshTokenValidityMs
        val expiredAt = Instant.now().plusMillis(expiryMillis)

        val entity = RefreshTokenEntity(
            userId = userId,
            hashedToken = hashedToken,
            expiredAt = expiredAt
        )

        refreshTokenRepository.save(entity)
    }

    /** Returns the Base64-encoded SHA-256 digest of the token's UTF-8 bytes. */
    private fun hashToken(token: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashedBytes = digest.digest(token.toByteArray())
        return Base64.encode(hashedBytes)
    }
}
