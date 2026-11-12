package io.github.neronguyenvn.nerochat.user.service

import io.github.neronguyenvn.nerochat.user.domain.exception.InvalidOtpException
import io.github.neronguyenvn.nerochat.user.domain.exception.MaxAttemptsExceededException
import io.github.neronguyenvn.nerochat.user.domain.exception.OtpExpiredException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.redisson.api.RAtomicLong
import org.redisson.api.RBucket
import org.redisson.api.RLock
import org.redisson.api.RedissonClient
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.TimeUnit

class RedissonOtpServiceTest {

    private val redisson: RedissonClient = mock(RedissonClient::class.java)
    private val pepper = "test-pepper"
    private lateinit var otpService: RedissonOtpService

    @Suppress("UNCHECKED_CAST")
    private val codeBucket: RBucket<String> = mock(RBucket::class.java) as RBucket<String>

    @Suppress("UNCHECKED_CAST")
    private val lockoutBucket: RBucket<Boolean> = mock(RBucket::class.java) as RBucket<Boolean>
    private val attemptsCounter: RAtomicLong = mock(RAtomicLong::class.java)
    private val lock: RLock = mock(RLock::class.java)

    private val testEmail = "test@example.com"

    @BeforeEach
    fun setUp() {
        otpService = RedissonOtpService(redisson = redisson, pepper = pepper)

        `when`(redisson.getBucket<String>(eq("auth:otp:code:verify:$testEmail"))).thenReturn(codeBucket)
        `when`(redisson.getBucket<Boolean>(eq("auth:otp:lockout:verify:$testEmail"))).thenReturn(lockoutBucket)
        `when`(redisson.getAtomicLong(eq("auth:otp:attempts:verify:$testEmail"))).thenReturn(attemptsCounter)
        `when`(redisson.getLock(eq("auth:otp:lock:verify:$testEmail"))).thenReturn(lock)

        `when`(lock.tryLock(anyLong(), anyLong(), any(TimeUnit::class.java) ?: TimeUnit.SECONDS)).thenReturn(true)
        `when`(lock.isHeldByCurrentThread).thenReturn(true)
    }

    private fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    @Test
    fun `generateAndStoreOtp returns 6-digit code and stores SHA-256 hash with 180s TTL`() {
        `when`(lockoutBucket.isExists).thenReturn(false)

        val code = otpService.generateAndStoreOtp(OtpType.VERIFY, testEmail)

        assertNotNull(code)
        assertEquals(6, code?.length)
        assertTrue(code!!.all { it.isDigit() })

        val expectedHash = sha256("$testEmail:$code:$pepper")
        verify(codeBucket).set(eq(expectedHash), eq(Duration.ofSeconds(180)))
        verify(attemptsCounter).delete()
    }

    @Test
    fun `generateAndStoreOtp suppresses generation when locked out (anti-enumeration)`() {
        `when`(lockoutBucket.isExists).thenReturn(true)

        val code = otpService.generateAndStoreOtp(OtpType.VERIFY, testEmail)

        assertNull(code)
        verify(codeBucket, never()).set(any() ?: "", any() ?: Duration.ZERO)
    }

    @Test
    fun `verifyOtp succeeds when correct code is submitted`() {
        val rawCode = "123456"
        val expectedHash = sha256("$testEmail:$rawCode:$pepper")

        `when`(lockoutBucket.isExists).thenReturn(false)
        `when`(codeBucket.get()).thenReturn(expectedHash)

        otpService.verifyOtp(OtpType.VERIFY, testEmail, rawCode)

        verify(lock).tryLock(2L, 2L, TimeUnit.SECONDS)
        verify(lock).unlock()
        verify(attemptsCounter, never()).incrementAndGet()
    }

    @Test
    fun `verifyOtp throws OtpExpiredException when code key is missing`() {
        `when`(lockoutBucket.isExists).thenReturn(false)
        `when`(codeBucket.get()).thenReturn(null)

        assertThrows<OtpExpiredException> {
            otpService.verifyOtp(OtpType.VERIFY, testEmail, "123456")
        }

        verify(lock).unlock()
    }

    @Test
    fun `verifyOtp throws MaxAttemptsExceededException when active lockout exists`() {
        `when`(lockoutBucket.isExists).thenReturn(true)
        `when`(lockoutBucket.remainTimeToLive()).thenReturn(240_000L) // 240s

        val ex = assertThrows<MaxAttemptsExceededException> {
            otpService.verifyOtp(OtpType.VERIFY, testEmail, "123456")
        }

        assertEquals(240L, ex.retryAfterSeconds)
        verify(codeBucket, never()).get()
        verify(lock).unlock()
    }

    @Test
    fun `verifyOtp increments attempt counter and throws InvalidOtpException on first failure`() {
        val rawCode = "123456"
        val wrongCode = "654321"
        val storedHash = sha256("$testEmail:$rawCode:$pepper")

        `when`(lockoutBucket.isExists).thenReturn(false)
        `when`(codeBucket.get()).thenReturn(storedHash)
        `when`(attemptsCounter.incrementAndGet()).thenReturn(1L)

        assertThrows<InvalidOtpException> {
            otpService.verifyOtp(OtpType.VERIFY, testEmail, wrongCode)
        }

        verify(attemptsCounter).incrementAndGet()
        verify(attemptsCounter).expire(eq(Duration.ofSeconds(180)))
        verify(lockoutBucket, never()).set(any() ?: false, any() ?: Duration.ZERO)
        verify(lock).unlock()
    }

    @Test
    fun `verifyOtp on 3rd failure deletes code, sets lockout with 300s TTL, and throws MaxAttemptsExceededException`() {
        val rawCode = "123456"
        val wrongCode = "654321"
        val storedHash = sha256("$testEmail:$rawCode:$pepper")

        `when`(lockoutBucket.isExists).thenReturn(false)
        `when`(codeBucket.get()).thenReturn(storedHash)
        `when`(attemptsCounter.incrementAndGet()).thenReturn(3L)

        val ex = assertThrows<MaxAttemptsExceededException> {
            otpService.verifyOtp(OtpType.VERIFY, testEmail, wrongCode)
        }

        assertEquals(300L, ex.retryAfterSeconds)
        verify(codeBucket).delete()
        verify(attemptsCounter).delete()
        verify(lockoutBucket).set(eq(true), eq(Duration.ofSeconds(300)))
        verify(lock).unlock()
    }

    @Test
    fun `deleteOtp deletes code and attempts buckets from Redis`() {
        otpService.deleteOtp(OtpType.VERIFY, testEmail)

        verify(codeBucket).delete()
        verify(attemptsCounter).delete()
    }
}
