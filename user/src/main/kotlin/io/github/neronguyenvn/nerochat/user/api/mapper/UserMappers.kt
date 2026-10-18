package io.github.neronguyenvn.nerochat.user.api.mapper

import io.github.neronguyenvn.nerochat.contract.auth.AuthenticatedUserDto
import io.github.neronguyenvn.nerochat.contract.auth.UserDto
import io.github.neronguyenvn.nerochat.user.domain.model.AuthenticatedUser
import io.github.neronguyenvn.nerochat.user.domain.model.User

/** Maps this user to an API response, including the display name and email-verification status. */
fun User.asDto(): UserDto {
    return UserDto(
        id = id.value,
        email = email,
        displayName = displayName,
        isEmailVerified = isEmailVerified,
    )
}

/** Maps the user profile and both tokens to the authentication response. */
fun AuthenticatedUser.asDto(): AuthenticatedUserDto {
    return AuthenticatedUserDto(
        user = user.asDto(),
        accessToken = accessToken,
        refreshToken = refreshToken,
    )
}
