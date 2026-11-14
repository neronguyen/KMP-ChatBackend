package io.github.neronguyenvn.nerochat.integration

import io.github.neronguyenvn.nerochat.user.domain.exception.InvalidOtpException
import io.github.neronguyenvn.nerochat.user.domain.exception.MaxAttemptsExceededException
import io.github.neronguyenvn.nerochat.user.service.OtpType
import io.github.neronguyenvn.nerochat.user.service.RedissonOtpService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.redisson.api.RedissonClient
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class RedissonOtpIntegrationTest : AbstractContainerIntegrationTest() {

    @Autowired
    private lateinit var redissonOtpService: RedissonOtpService

    @Autowired
    private lateinit var redissonClient: RedissonClient

    @Test
    fun `concurrent verifyOtp attempts serialize via RLock and trigger 300s lockout after exactly 3 failures`() {
        val email = "concurrent-${UUID.randomUUID()}@example.com"
        val validCode = redissonOtpService.generateAndStoreOtp(OtpType.VERIFY, email)
        assertNotNull(validCode)

        val wrongCode = if (validCode == "000000") "999999" else "000000"
        val threadCount = 10
        val executor = Executors.newFixedThreadPool(threadCount)
        val readyLatch = CountDownLatch(threadCount)
        val startGate = CountDownLatch(1)
        val doneLatch = CountDownLatch(threadCount)
        val thrownExceptions = ConcurrentLinkedQueue<Throwable>()

        try {
            repeat(threadCount) {
                executor.submit {
                    readyLatch.countDown()
                    startGate.await(5, TimeUnit.SECONDS)
                    try {
                        redissonOtpService.verifyOtp(OtpType.VERIFY, email, wrongCode)
                    } catch (t: Throwable) {
                        thrownExceptions.add(t)
                    } finally {
                        doneLatch.countDown()
                    }
                }
            }

            assertTrue(readyLatch.await(5, TimeUnit.SECONDS))
            startGate.countDown()
            assertTrue(doneLatch.await(15, TimeUnit.SECONDS))
        } finally {
            executor.shutdownNow()
        }

        assertEquals(threadCount, thrownExceptions.size)
        val invalidOtpCount = thrownExceptions.count { it is InvalidOtpException }
        val maxAttemptsCount = thrownExceptions.count { it is MaxAttemptsExceededException }

        // Attempts 1 and 2 throw InvalidOtpException; attempt 3 locks out and throws MaxAttemptsExceededException;
        // remaining 7 attempts see active lockout and throw MaxAttemptsExceededException.
        assertEquals(2, invalidOtpCount, "Expected exactly 2 InvalidOtpException before 3rd attempt lockout")
        assertEquals(8, maxAttemptsCount, "Expected remaining 8 attempts to fail with MaxAttemptsExceededException")

        val codeBucket = redissonClient.getBucket<String>("auth:otp:code:verify:$email")
        val attemptsCounter = redissonClient.getAtomicLong("auth:otp:attempts:verify:$email")
        val lockoutBucket = redissonClient.getBucket<Boolean>("auth:otp:lockout:verify:$email")

        assertFalse(codeBucket.isExists, "OTP code key should be deleted after max attempts")
        assertFalse(attemptsCounter.isExists, "Attempts counter key should be deleted after max attempts")
        assertTrue(lockoutBucket.isExists, "Lockout key should be created after 3 failed attempts")

        val remainTtlMs = lockoutBucket.remainTimeToLive()
        assertTrue(
            remainTtlMs in 240_000L..300_000L,
            "Expected lockout TTL near ${RedissonOtpService.LOCKOUT_TTL_SECONDS}s, got ${remainTtlMs}ms"
        )

        assertNull(
            redissonOtpService.generateAndStoreOtp(OtpType.VERIFY, email),
            "OTP regeneration must be suppressed while account is locked out"
        )
    }

    @Test
    fun `happy path generates verifies and deletes OTP keys from Redis`() {
        val email = "happy-${UUID.randomUUID()}@example.com"
        val code = redissonOtpService.generateAndStoreOtp(OtpType.VERIFY, email)
        assertNotNull(code)
        assertEquals(6, code!!.length)
        assertTrue(code.all { it.isDigit() })

        val codeBucket = redissonClient.getBucket<String>("auth:otp:code:verify:$email")
        val attemptsCounter = redissonClient.getAtomicLong("auth:otp:attempts:verify:$email")
        assertTrue(codeBucket.isExists)

        redissonOtpService.verifyOtp(OtpType.VERIFY, email, code)
        redissonOtpService.deleteOtp(OtpType.VERIFY, email)

        assertFalse(codeBucket.isExists)
        assertFalse(attemptsCounter.isExists)
    }
}
