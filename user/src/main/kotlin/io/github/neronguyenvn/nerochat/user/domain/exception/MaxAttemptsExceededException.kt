package io.github.neronguyenvn.nerochat.user.domain.exception

class MaxAttemptsExceededException(
    val retryAfterSeconds: Long? = null,
    override val message: String = "Maximum verification attempts exceeded. Please try again later.",
) : RuntimeException(message)
