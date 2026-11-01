package io.github.neronguyenvn.nerochat.user.service

import io.github.neronguyenvn.nerochat.domain.event.UserEvent
import io.github.neronguyenvn.nerochat.domain.exception.InvalidTokenException
import io.github.neronguyenvn.nerochat.infra.messagequeue.EventPublisher
import io.github.neronguyenvn.nerochat.user.domain.exception.MaxAttemptsExceededException
import io.github.neronguyenvn.nerochat.user.domain.exception.OtpExpiredException
import io.github.neronguyenvn.nerochat.user.domain.exception.SamePasswordException
import io.github.neronguyenvn.nerochat.user.domain.model.AuthTokenType
import io.github.neronguyenvn.nerochat.user.infra.database.model.AuthTokenEntity
import io.github.neronguyenvn.nerochat.user.infra.database.model.UserEntity
import io.github.neronguyenvn.nerochat.user.infra.database.model.userId
import io.github.neronguyenvn.nerochat.user.infra.database.repository.AuthTokenRepository
import io.github.neronguyenvn.nerochat.user.infra.database.repository.RefreshTokenRepository
import io.github.neronguyenvn.nerochat.user.infra.database.repository.UserRepository
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.*
import org.springframework.security.crypto.password.PasswordEncoder
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

class PasswordResetServiceTest {

    private lateinit var authTokenRepository: AuthTokenRepository
    private lateinit var userRepository: UserRepository
    private lateinit var refreshTokenRepository: RefreshTokenRepository
    private lateinit var passwordEncoder: PasswordEncoder
    private lateinit var eventPublisher: EventPublisher
    private lateinit var service: PasswordResetService

    private val testEmail = "test@example.com"
    private lateinit var testUser: UserEntity

    @BeforeEach
    fun setUp() {
        authTokenRepository = mock(AuthTokenRepository::class.java)
        userRepository = mock(UserRepository::class.java)
        refreshTokenRepository = mock(RefreshTokenRepository::class.java)
        passwordEncoder = mock(PasswordEncoder::class.java)
        eventPublisher = mock(EventPublisher::class.java)

        service = PasswordResetService(
            authTokenRepository = authTokenRepository,
            userRepository = userRepository,
            refreshTokenRepository = refreshTokenRepository,
            passwordEncoder = passwordEncoder,
            eventPublisher = eventPublisher,
            expiryMinutes = 10
        )

        testUser = UserEntity(
            id = UUID.randomUUID(),
            email = testEmail,
            displayName = "Test User",
            hashedPassword = "old_hashed_password"
        )
        `when`(userRepository.findByEmail(testEmail)).thenReturn(testUser)
    }

    @Test
    fun `requestPasswordReset generates 6-digit OTP and publishes event`() {
        val captor = ArgumentCaptor.forClass(AuthTokenEntity::class.java)
        `when`(authTokenRepository.save(captor.capture())).thenAnswer { it.arguments[0] }

        service.requestPasswordReset(testEmail)

        verify(authTokenRepository).invalidatePasswordResetTokens(testUser)
        val savedEntity = captor.value
        assertEquals(6, savedEntity.token.length)
        assertTrue(savedEntity.token.all { it.isDigit() })
        assertEquals(0, savedEntity.attempts)
        assertEquals(AuthTokenType.PasswordReset, savedEntity.tokenType)
        val eventCaptor = ArgumentCaptor.forClass(UserEvent.RequestResetPassword::class.java)
        verify(eventPublisher).publish(
            eventCaptor.capture() ?: UserEvent.RequestResetPassword(
                testUser.userId,
                testEmail,
                testUser.displayName,
                "123456",
                kotlin.time.Duration.ZERO
            )
        )
        assertEquals(testEmail, eventCaptor.value.email)
    }

    @Test
    fun `resetPassword succeeds with correct code and updates password`() {
        val tokenEntity = AuthTokenEntity(
            token = "654321",
            tokenType = AuthTokenType.PasswordReset,
            expiredAt = Instant.now().plus(10, ChronoUnit.MINUTES),
            user = testUser
        )
        `when`(authTokenRepository.findFirstByUserAndTokenTypeAndUsedAtIsNullOrderByCreatedAtDesc(
            testUser,
            AuthTokenType.PasswordReset
        )).thenReturn(tokenEntity)
        `when`(passwordEncoder.matches("newPassword123", "old_hashed_password")).thenReturn(false)
        `when`(passwordEncoder.encode("newPassword123")).thenReturn("new_hashed_password")

        service.resetPassword(testEmail, "654321", "newPassword123")

        assertEquals("new_hashed_password", testUser.hashedPassword)
        assertTrue(tokenEntity.isUsed())
        verify(refreshTokenRepository).deleteByUserId(testUser.id!!)
        verify(userRepository).save(testUser)
        verify(authTokenRepository).save(tokenEntity)
    }

    @Test
    fun `resetPassword throws SamePasswordException if password unchanged`() {
        val tokenEntity = AuthTokenEntity(
            token = "654321",
            tokenType = AuthTokenType.PasswordReset,
            expiredAt = Instant.now().plus(10, ChronoUnit.MINUTES),
            user = testUser
        )
        `when`(authTokenRepository.findFirstByUserAndTokenTypeAndUsedAtIsNullOrderByCreatedAtDesc(
            testUser,
            AuthTokenType.PasswordReset
        )).thenReturn(tokenEntity)
        `when`(passwordEncoder.matches("samePassword123", "old_hashed_password")).thenReturn(true)

        assertThrows<SamePasswordException> {
            service.resetPassword(testEmail, "654321", "samePassword123")
        }
    }

    @Test
    fun `resetPassword tracks failed attempts and locks after 5 attempts`() {
        val tokenEntity = AuthTokenEntity(
            token = "654321",
            tokenType = AuthTokenType.PasswordReset,
            expiredAt = Instant.now().plus(10, ChronoUnit.MINUTES),
            user = testUser,
            attempts = 4
        )
        `when`(authTokenRepository.findFirstByUserAndTokenTypeAndUsedAtIsNullOrderByCreatedAtDesc(
            testUser,
            AuthTokenType.PasswordReset
        )).thenReturn(tokenEntity)

        assertThrows<MaxAttemptsExceededException> {
            service.resetPassword(testEmail, "000000", "newPassword123")
        }

        assertEquals(5, tokenEntity.attempts)
        assertTrue(tokenEntity.isUsed())
        verify(authTokenRepository).save(tokenEntity)
    }
}
