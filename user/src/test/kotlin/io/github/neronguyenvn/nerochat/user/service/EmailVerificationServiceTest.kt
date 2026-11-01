package io.github.neronguyenvn.nerochat.user.service

import io.github.neronguyenvn.nerochat.domain.event.UserEvent
import io.github.neronguyenvn.nerochat.domain.exception.InvalidTokenException
import io.github.neronguyenvn.nerochat.domain.type.UserId
import io.github.neronguyenvn.nerochat.infra.messagequeue.EventPublisher
import io.github.neronguyenvn.nerochat.user.domain.exception.MaxAttemptsExceededException
import io.github.neronguyenvn.nerochat.user.domain.exception.OtpExpiredException
import io.github.neronguyenvn.nerochat.user.domain.model.AuthTokenType
import io.github.neronguyenvn.nerochat.user.infra.database.model.AuthTokenEntity
import io.github.neronguyenvn.nerochat.user.infra.database.model.UserEntity
import io.github.neronguyenvn.nerochat.user.infra.database.model.userId
import io.github.neronguyenvn.nerochat.user.infra.database.repository.AuthTokenRepository
import io.github.neronguyenvn.nerochat.user.infra.database.repository.UserRepository
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.*
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

class EmailVerificationServiceTest {

    private lateinit var authTokenRepository: AuthTokenRepository
    private lateinit var userRepository: UserRepository
    private lateinit var eventPublisher: EventPublisher
    private lateinit var service: EmailVerificationService

    private val testEmail = "test@example.com"
    private lateinit var testUser: UserEntity

    @BeforeEach
    fun setUp() {
        authTokenRepository = mock(AuthTokenRepository::class.java)
        userRepository = mock(UserRepository::class.java)
        eventPublisher = mock(EventPublisher::class.java)
        service = EmailVerificationService(
            authTokenRepository = authTokenRepository,
            userRepository = userRepository,
            eventPublisher = eventPublisher,
            expiryMinutes = 10
        )

        testUser = UserEntity(
            id = UUID.randomUUID(),
            email = testEmail,
            displayName = "Test User",
            hashedPassword = "hash",
            isEmailVerified = false
        )
        `when`(userRepository.findByEmail(testEmail)).thenReturn(testUser)
    }

    @Test
    fun `createVerificationToken generates 6-digit OTP and saves entity`() {
        val captor = ArgumentCaptor.forClass(AuthTokenEntity::class.java)
        `when`(authTokenRepository.save(captor.capture())).thenAnswer { it.arguments[0] }

        val result = service.createVerificationToken(testEmail)

        assertEquals(6, result.token.length)
        assertTrue(result.token.all { it.isDigit() })
        verify(authTokenRepository).invalidateEmailVerificationTokens(testUser)
        val savedEntity = captor.value
        assertEquals(0, savedEntity.attempts)
        assertEquals(AuthTokenType.EmailVerification, savedEntity.tokenType)
    }

    @Test
    fun `verifyEmail succeeds with correct code and marks user verified`() {
        val tokenEntity = AuthTokenEntity(
            token = "123456",
            tokenType = AuthTokenType.EmailVerification,
            expiredAt = Instant.now().plus(10, ChronoUnit.MINUTES),
            user = testUser
        )
        `when`(authTokenRepository.findFirstByUserAndTokenTypeAndUsedAtIsNullOrderByCreatedAtDesc(
            testUser,
            AuthTokenType.EmailVerification
        )).thenReturn(tokenEntity)

        service.verifyEmail(testEmail, "123456")

        assertTrue(testUser.isEmailVerified)
        assertTrue(tokenEntity.isUsed())
        verify(userRepository).save(testUser)
        verify(authTokenRepository).save(tokenEntity)
        val eventCaptor = ArgumentCaptor.forClass(UserEvent.Verified::class.java)
        verify(eventPublisher).publish(
            eventCaptor.capture() ?: UserEvent.Verified(testUser.userId, testEmail, testUser.displayName)
        )
        assertEquals(testEmail, eventCaptor.value.email)
    }

    @Test
    fun `verifyEmail throws OtpExpiredException when expired`() {
        val tokenEntity = AuthTokenEntity(
            token = "123456",
            tokenType = AuthTokenType.EmailVerification,
            expiredAt = Instant.now().minus(1, ChronoUnit.MINUTES),
            user = testUser
        )
        `when`(authTokenRepository.findFirstByUserAndTokenTypeAndUsedAtIsNullOrderByCreatedAtDesc(
            testUser,
            AuthTokenType.EmailVerification
        )).thenReturn(tokenEntity)

        assertThrows<OtpExpiredException> {
            service.verifyEmail(testEmail, "123456")
        }
        assertFalse(testUser.isEmailVerified)
    }

    @Test
    fun `verifyEmail tracks failed attempts and locks token after 5 attempts`() {
        val tokenEntity = AuthTokenEntity(
            token = "123456",
            tokenType = AuthTokenType.EmailVerification,
            expiredAt = Instant.now().plus(10, ChronoUnit.MINUTES),
            user = testUser,
            attempts = 4
        )
        `when`(authTokenRepository.findFirstByUserAndTokenTypeAndUsedAtIsNullOrderByCreatedAtDesc(
            testUser,
            AuthTokenType.EmailVerification
        )).thenReturn(tokenEntity)

        // 5th failed attempt should trigger MaxAttemptsExceededException and mark token used
        assertThrows<MaxAttemptsExceededException> {
            service.verifyEmail(testEmail, "000000")
        }

        assertEquals(5, tokenEntity.attempts)
        assertTrue(tokenEntity.isUsed())
        verify(authTokenRepository).save(tokenEntity)
    }

    @Test
    fun `verifyEmail increments attempt counter on wrong code when below limit`() {
        val tokenEntity = AuthTokenEntity(
            token = "123456",
            tokenType = AuthTokenType.EmailVerification,
            expiredAt = Instant.now().plus(10, ChronoUnit.MINUTES),
            user = testUser,
            attempts = 1
        )
        `when`(authTokenRepository.findFirstByUserAndTokenTypeAndUsedAtIsNullOrderByCreatedAtDesc(
            testUser,
            AuthTokenType.EmailVerification
        )).thenReturn(tokenEntity)

        assertThrows<InvalidTokenException> {
            service.verifyEmail(testEmail, "000000")
        }

        assertEquals(2, tokenEntity.attempts)
        assertFalse(tokenEntity.isUsed())
        verify(authTokenRepository).save(tokenEntity)
    }
}
