package io.github.neronguyenvn.nerochat.user.service

import io.github.neronguyenvn.nerochat.domain.event.Event
import io.github.neronguyenvn.nerochat.domain.event.UserEvent
import io.github.neronguyenvn.nerochat.domain.type.UserId
import io.github.neronguyenvn.nerochat.infra.messagequeue.EventPublisher
import io.github.neronguyenvn.nerochat.user.domain.exception.InvalidOtpException
import io.github.neronguyenvn.nerochat.user.domain.exception.SamePasswordException
import io.github.neronguyenvn.nerochat.user.domain.exception.UserNotFoundException
import io.github.neronguyenvn.nerochat.user.infra.database.model.UserEntity
import io.github.neronguyenvn.nerochat.user.infra.database.repository.RefreshTokenRepository
import io.github.neronguyenvn.nerochat.user.infra.database.repository.UserRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.*
import org.springframework.security.crypto.password.PasswordEncoder
import java.util.*

class PasswordResetServiceTest {

    private val otpService: RedissonOtpService = mock(RedissonOtpService::class.java)
    private val userRepository: UserRepository = mock(UserRepository::class.java)
    private val refreshTokenRepository: RefreshTokenRepository = mock(RefreshTokenRepository::class.java)
    private val passwordEncoder: PasswordEncoder = mock(PasswordEncoder::class.java)
    private val eventPublisher: EventPublisher = mock(EventPublisher::class.java)

    private lateinit var service: PasswordResetService
    private val testEmail = "test@example.com"
    private val userId = UUID.randomUUID()
    private val dummyEvent: Event = UserEvent.Verified(UserId(UUID.randomUUID()), "dummy@example.com", "Dummy")

    @BeforeEach
    fun setUp() {
        service = PasswordResetService(
            otpService = otpService,
            userRepository = userRepository,
            refreshTokenRepository = refreshTokenRepository,
            passwordEncoder = passwordEncoder,
            eventPublisher = eventPublisher,
        )
    }

    @Test
    fun `requestPasswordReset throws UserNotFoundException when user does not exist`() {
        `when`(userRepository.findByEmail(testEmail)).thenReturn(null)

        assertThrows<UserNotFoundException> {
            service.requestPasswordReset(testEmail)
        }

        verify(eventPublisher, never()).publish(any() ?: dummyEvent)
    }

    @Test
    fun `requestPasswordReset suppresses email when lockout is active`() {
        val user = UserEntity(
            id = userId,
            email = testEmail,
            displayName = "Test User",
            hashedPassword = "hash"
        )
        `when`(userRepository.findByEmail(testEmail)).thenReturn(user)
        `when`(otpService.generateAndStoreOtp(OtpType.RESET, testEmail)).thenReturn(null)

        service.requestPasswordReset(testEmail)

        verify(eventPublisher, never()).publish(any() ?: dummyEvent)
    }

    @Test
    fun `requestPasswordReset publishes RequestResetPassword on happy path`() {
        val user = UserEntity(
            id = userId,
            email = testEmail,
            displayName = "Test User",
            hashedPassword = "hash"
        )
        `when`(userRepository.findByEmail(testEmail)).thenReturn(user)
        `when`(otpService.generateAndStoreOtp(OtpType.RESET, testEmail)).thenReturn("654321")

        service.requestPasswordReset(testEmail)

        val captor = ArgumentCaptor.forClass(Event::class.java)
        verify(eventPublisher).publish(captor.capture() ?: dummyEvent)
        val event = captor.value as UserEvent.RequestResetPassword
        assertEquals(UserId(userId), event.userId)
        assertEquals(testEmail, event.email)
        assertEquals("654321", event.otpCode)
    }

    @Test
    fun `resetPassword verifies OTP first and prevents password oracle enumeration`() {
        val user = UserEntity(
            id = userId,
            email = testEmail,
            displayName = "Test User",
            hashedPassword = "oldHash"
        )
        `when`(userRepository.findByEmail(testEmail)).thenReturn(user)
        doThrow(InvalidOtpException()).`when`(otpService).verifyOtp(OtpType.RESET, testEmail, "wrong-code")

        assertThrows<InvalidOtpException> {
            service.resetPassword(testEmail, "wrong-code", "matchingOldPassword")
        }

        // Verify password comparison is NEVER called when OTP is invalid
        verify(passwordEncoder, never()).matches(any() ?: "", any() ?: "")
        verify(refreshTokenRepository, never()).deleteByUserId(any() ?: UUID.randomUUID())
        verify(otpService, never()).deleteOtp(any() ?: OtpType.RESET, any() ?: "")
    }

    @Test
    fun `resetPassword throws SamePasswordException when new password matches existing password`() {
        val user = UserEntity(
            id = userId,
            email = testEmail,
            displayName = "Test User",
            hashedPassword = "existingHash"
        )
        `when`(userRepository.findByEmail(testEmail)).thenReturn(user)
        `when`(passwordEncoder.matches("ExistingPassword123!", "existingHash")).thenReturn(true)

        assertThrows<SamePasswordException> {
            service.resetPassword(testEmail, "123456", "ExistingPassword123!")
        }

        verify(otpService).verifyOtp(OtpType.RESET, testEmail, "123456")
        verify(refreshTokenRepository, never()).deleteByUserId(any() ?: UUID.randomUUID())
        // OTP key preserved in Redis for retry
        verify(otpService, never()).deleteOtp(any() ?: OtpType.RESET, any() ?: "")
    }

    @Test
    fun `resetPassword happy path updates password, deletes refresh tokens, and deletes OTP`() {
        val user = UserEntity(
            id = userId,
            email = testEmail,
            displayName = "Test User",
            hashedPassword = "oldHash"
        )
        `when`(userRepository.findByEmail(testEmail)).thenReturn(user)
        `when`(passwordEncoder.matches("NewPassword123!", "oldHash")).thenReturn(false)
        `when`(passwordEncoder.encode("NewPassword123!")).thenReturn("newHashedPassword")

        service.resetPassword(testEmail, "123456", "NewPassword123!")

        verify(otpService).verifyOtp(OtpType.RESET, testEmail, "123456")
        assertEquals("newHashedPassword", user.hashedPassword)
        verify(userRepository).save(user)
        verify(refreshTokenRepository).deleteByUserId(userId)
        verify(otpService).deleteOtp(OtpType.RESET, testEmail)
    }
}
