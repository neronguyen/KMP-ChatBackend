package io.github.neronguyenvn.nerochat.user.service

import io.github.neronguyenvn.nerochat.domain.event.Event
import io.github.neronguyenvn.nerochat.domain.event.UserEvent
import io.github.neronguyenvn.nerochat.domain.type.UserId
import io.github.neronguyenvn.nerochat.infra.messagequeue.EventPublisher
import io.github.neronguyenvn.nerochat.user.infra.database.model.UserEntity
import io.github.neronguyenvn.nerochat.user.infra.database.repository.UserRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.*
import java.util.*

class EmailVerificationServiceTest {

    private val otpService: RedissonOtpService = mock(RedissonOtpService::class.java)
    private val userRepository: UserRepository = mock(UserRepository::class.java)
    private val eventPublisher: EventPublisher = mock(EventPublisher::class.java)

    private lateinit var service: EmailVerificationService
    private val testEmail = "test@example.com"
    private val userId = UUID.randomUUID()
    private val dummyEvent: Event = UserEvent.Verified(UserId(UUID.randomUUID()), "dummy@example.com", "Dummy")

    @BeforeEach
    fun setUp() {
        service = EmailVerificationService(
            otpService = otpService,
            userRepository = userRepository,
            eventPublisher = eventPublisher,
        )
    }

    @Test
    fun `createVerificationOtp delegates to otpService and returns raw code`() {
        `when`(otpService.generateAndStoreOtp(OtpType.VERIFY, testEmail)).thenReturn("123456")

        val code = service.createVerificationOtp(testEmail)

        assertEquals("123456", code)
        verify(otpService).generateAndStoreOtp(OtpType.VERIFY, testEmail)
    }

    @Test
    fun `resendVerificationEmail publishes RequestResendVerification when user unverified`() {
        val user = UserEntity(
            id = userId,
            email = testEmail,
            displayName = "Test User",
            hashedPassword = "hash",
            isEmailVerified = false
        )
        `when`(userRepository.findByEmail(testEmail)).thenReturn(user)
        `when`(otpService.generateAndStoreOtp(OtpType.VERIFY, testEmail)).thenReturn("654321")

        service.resendVerificationEmail(testEmail)

        val captor = ArgumentCaptor.forClass(Event::class.java)
        verify(eventPublisher).publish(captor.capture() ?: dummyEvent)
        val event = captor.value as UserEvent.RequestResendVerification
        assertEquals(UserId(userId), event.userId)
        assertEquals(testEmail, event.email)
        assertEquals("654321", event.otpCode)
    }

    @Test
    fun `resendVerificationEmail suppresses event when user is already verified`() {
        val user = UserEntity(
            id = userId,
            email = testEmail,
            displayName = "Test User",
            hashedPassword = "hash",
            isEmailVerified = true
        )
        `when`(userRepository.findByEmail(testEmail)).thenReturn(user)

        service.resendVerificationEmail(testEmail)

        verify(otpService, never()).generateAndStoreOtp(any() ?: OtpType.VERIFY, any() ?: "")
        verify(eventPublisher, never()).publish(any() ?: dummyEvent)
    }

    @Test
    fun `resendVerificationEmail suppresses event when account is locked out`() {
        val user = UserEntity(
            id = userId,
            email = testEmail,
            displayName = "Test User",
            hashedPassword = "hash",
            isEmailVerified = false
        )
        `when`(userRepository.findByEmail(testEmail)).thenReturn(user)
        `when`(otpService.generateAndStoreOtp(OtpType.VERIFY, testEmail)).thenReturn(null)

        service.resendVerificationEmail(testEmail)

        verify(eventPublisher, never()).publish(any() ?: dummyEvent)
    }

    @Test
    fun `verifyEmail verifies OTP, updates user, publishes Verified, and deletes OTP`() {
        val user = UserEntity(
            id = userId,
            email = testEmail,
            displayName = "Test User",
            hashedPassword = "hash",
            isEmailVerified = false
        )
        `when`(userRepository.findByEmail(testEmail)).thenReturn(user)

        service.verifyEmail(testEmail, "123456")

        verify(otpService).verifyOtp(OtpType.VERIFY, testEmail, "123456")
        assertTrue(user.isEmailVerified)
        verify(userRepository).save(user)

        val captor = ArgumentCaptor.forClass(Event::class.java)
        verify(eventPublisher).publish(captor.capture() ?: dummyEvent)
        val event = captor.value as UserEvent.Verified
        assertEquals(UserId(userId), event.userId)
        assertEquals(testEmail, event.email)

        verify(otpService).deleteOtp(OtpType.VERIFY, testEmail)
    }
}
