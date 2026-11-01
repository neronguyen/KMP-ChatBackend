package io.github.neronguyenvn.nerochat.user.domain.exception

class MaxAttemptsExceededException(
    override val message: String = "Maximum verification attempts exceeded. Please request a new code."
) : RuntimeException(message)
