package io.github.neronguyenvn.nerochat.domain.exception

class RateLimitExceededException(
    val resetsInSeconds: Long,
    override val message: String = "Rate limit exceeded. Please try again in $resetsInSeconds seconds."
) : RuntimeException(message)
