package io.github.neronguyenvn.nerochat.user.domain.exception

class OtpExpiredException(
    override val message: String = "Verification code has expired",
) : RuntimeException(message)
