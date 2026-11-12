package io.github.neronguyenvn.nerochat.user.domain.exception

class InvalidOtpException(
    override val message: String = "Invalid verification code",
) : RuntimeException(message)
