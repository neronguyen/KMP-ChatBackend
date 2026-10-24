package io.github.neronguyenvn.nerochat.user.domain.exception

class UserNotFoundException private constructor(
    override val message: String
) : RuntimeException(message) {

    companion object {
        private const val DEFAULT_EMAIL_MESSAGE = "A user with this email does not exist"
        private const val DEFAULT_ID_MESSAGE = "A user with this ID does not exist"

        fun byEmail(): UserNotFoundException = UserNotFoundException(DEFAULT_EMAIL_MESSAGE)

        fun byId(): UserNotFoundException = UserNotFoundException(DEFAULT_ID_MESSAGE)
    }
}
