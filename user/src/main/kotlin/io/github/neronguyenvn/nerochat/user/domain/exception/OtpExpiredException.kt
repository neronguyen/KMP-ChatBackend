package io.github.neronguyenvn.nerochat.user.domain.exception

class OtpExpiredException(
    override val message: String = "OTP code has expired"
) : RuntimeException(message)
