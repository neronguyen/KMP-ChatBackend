package io.github.neronguyenvn.nerochat.user.infra.security

import java.security.SecureRandom
import java.util.*

object SecureTokenGenerator {

    private const val TOKEN_BYTE_LENGTH = 32
    private val secureRandom = SecureRandom()

    /** Returns 32 cryptographically random bytes as URL-safe Base64 without padding. */
    fun generate(): String {
        val tokenBytes = ByteArray(TOKEN_BYTE_LENGTH)
        secureRandom.nextBytes(tokenBytes)

        return Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(tokenBytes)
    }

    /** Returns a cryptographically random 6-digit numeric OTP string. */
    fun generateOtp(): String {
        val code = secureRandom.nextInt(1_000_000)
        return "%06d".format(code)
    }
}
