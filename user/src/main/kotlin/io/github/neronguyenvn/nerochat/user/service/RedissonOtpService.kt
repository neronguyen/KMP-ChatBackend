package io.github.neronguyenvn.nerochat.user.service

import io.github.neronguyenvn.nerochat.domain.util.normalizeEmail
import io.github.neronguyenvn.nerochat.user.domain.exception.InvalidOtpException
import io.github.neronguyenvn.nerochat.user.domain.exception.MaxAttemptsExceededException
import io.github.neronguyenvn.nerochat.user.domain.exception.OtpExpiredException
import org.redisson.api.RedissonClient
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.util.Locale
import java.util.concurrent.TimeUnit

enum class OtpType(val key: String) {
    VERIFY("verify"),
    RESET("reset"),
}

@Service
class RedissonOtpService(
    private val redisson: RedissonClient,
    @Value("\${otp.pepper}")
    private val pepper: String,
) {
    private val secureRandom = SecureRandom()

    /**
     * Generates a 6-digit numeric OTP and stores its salted SHA-256 hash in Redis with a 180s TTL.
     * If the email is currently in a lockout state, generation is suppressed and null is returned (anti-enumeration).
     * Explicitly resets/deletes any existing attempts counter so the fresh OTP starts with 0 failed attempts.
     */
    fun generateAndStoreOtp(type: OtpType, email: String): String? {
        val normalizedEmail = normalizeEmail(email)
        val lockoutKey = "auth:otp:lockout:${type.key}:$normalizedEmail"
        val lockoutBucket = redisson.getBucket<Boolean>(lockoutKey)
        if (lockoutBucket.isExists) {
            return null
        }

        val rawCode = String.format(Locale.ROOT, "%06d", secureRandom.nextInt(1_000_000))
        val hash = hashOtp(normalizedEmail, rawCode)

        val codeKey = "auth:otp:code:${type.key}:$normalizedEmail"
        val attemptsKey = "auth:otp:attempts:${type.key}:$normalizedEmail"

        val codeBucket = redisson.getBucket<String>(codeKey)
        codeBucket.set(hash, Duration.ofSeconds(OTP_TTL_SECONDS))

        val attemptsCounter = redisson.getAtomicLong(attemptsKey)
        attemptsCounter.delete()

        return rawCode
    }

    /**
     * Verifies the provided 6-digit OTP under a distributed lock ("auth:otp:lock:$type:$email") with 2s lease.
     *
     * 1. Acquires lock with 2s lease time.
     * 2. If locked out, throws [MaxAttemptsExceededException] with remaining lockout seconds.
     * 3. If code key does not exist / expired, throws [OtpExpiredException].
     * 4. Constant-time comparison between stored hash and hash of incoming code.
     * 5. If matching: returns successfully (keys are deleted later via [deleteOtp] upon successful DB commit).
     * 6. If mismatch: increments attempts counter, renews TTL to 180s.
     *    - If attempts >= 3: deletes code & attempts keys, sets lockout key with 300s TTL, throws [MaxAttemptsExceededException(300L)].
     *    - If attempts < 3: throws [InvalidOtpException].
     */
    fun verifyOtp(type: OtpType, email: String, rawCode: String) {
        val normalizedEmail = normalizeEmail(email)
        val lockKey = "auth:otp:lock:${type.key}:$normalizedEmail"
        val lock = redisson.getLock(lockKey)

        val acquired = try {
            lock.tryLock(LOCK_WAIT_SECONDS, LOCK_LEASE_SECONDS, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw RuntimeException("Interrupted while acquiring OTP lock", e)
        }

        if (!acquired) {
            throw RuntimeException("Server is busy. Please try again later.")
        }

        try {
            val lockoutKey = "auth:otp:lockout:${type.key}:$normalizedEmail"
            val lockoutBucket = redisson.getBucket<Boolean>(lockoutKey)
            if (lockoutBucket.isExists) {
                val remainTtl = lockoutBucket.remainTimeToLive()
                val retryAfter = if (remainTtl > 0) maxOf(1L, remainTtl / 1000) else LOCKOUT_TTL_SECONDS
                throw MaxAttemptsExceededException(retryAfterSeconds = retryAfter)
            }

            val codeKey = "auth:otp:code:${type.key}:$normalizedEmail"
            val codeBucket = redisson.getBucket<String>(codeKey)
            val storedHash = codeBucket.get() ?: throw OtpExpiredException()

            val incomingHash = hashOtp(normalizedEmail, rawCode)
            val isMatch = MessageDigest.isEqual(
                storedHash.toByteArray(Charsets.UTF_8),
                incomingHash.toByteArray(Charsets.UTF_8)
            )

            if (isMatch) {
                return
            }

            // On mismatch:
            val attemptsKey = "auth:otp:attempts:${type.key}:$normalizedEmail"
            val attemptsCounter = redisson.getAtomicLong(attemptsKey)
            val currentAttempts = attemptsCounter.incrementAndGet()
            attemptsCounter.expire(Duration.ofSeconds(OTP_TTL_SECONDS))

            if (currentAttempts >= MAX_ATTEMPTS) {
                codeBucket.delete()
                attemptsCounter.delete()
                lockoutBucket.set(true, Duration.ofSeconds(LOCKOUT_TTL_SECONDS))
                throw MaxAttemptsExceededException(retryAfterSeconds = LOCKOUT_TTL_SECONDS)
            } else {
                throw InvalidOtpException()
            }
        } finally {
            if (lock.isHeldByCurrentThread) {
                lock.unlock()
            }
        }
    }

    /**
     * Deletes code and attempts keys from Redis upon successful transaction completion.
     */
    fun deleteOtp(type: OtpType, email: String) {
        val normalizedEmail = normalizeEmail(email)
        val codeKey = "auth:otp:code:${type.key}:$normalizedEmail"
        val attemptsKey = "auth:otp:attempts:${type.key}:$normalizedEmail"
        redisson.getBucket<String>(codeKey).delete()
        redisson.getAtomicLong(attemptsKey).delete()
    }

    private fun hashOtp(email: String, rawCode: String): String {
        val payload = "$email:$rawCode:$pepper"
        val digest = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val OTP_TTL_SECONDS = 180L
        const val LOCKOUT_TTL_SECONDS = 300L
        const val MAX_ATTEMPTS = 3L
        const val LOCK_WAIT_SECONDS = 2L
        const val LOCK_LEASE_SECONDS = 2L
    }
}
