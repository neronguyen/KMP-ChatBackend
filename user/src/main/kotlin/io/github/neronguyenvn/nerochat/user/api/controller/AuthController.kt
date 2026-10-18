package io.github.neronguyenvn.nerochat.user.api.controller

import io.github.neronguyenvn.nerochat.api.util.requesterId
import io.github.neronguyenvn.nerochat.contract.auth.AuthenticatedUserDto
import io.github.neronguyenvn.nerochat.contract.auth.UserDto
import io.github.neronguyenvn.nerochat.user.api.config.IpRateLimiting
import io.github.neronguyenvn.nerochat.user.api.mapper.asDto
import io.github.neronguyenvn.nerochat.user.api.request.ChangePasswordRequest
import io.github.neronguyenvn.nerochat.user.api.request.EmailRequest
import io.github.neronguyenvn.nerochat.user.api.request.LoginRequest
import io.github.neronguyenvn.nerochat.user.api.request.RefreshTokenRequest
import io.github.neronguyenvn.nerochat.user.api.request.RegisterRequest
import io.github.neronguyenvn.nerochat.user.api.request.ResetPasswordRequest
import io.github.neronguyenvn.nerochat.user.domain.exception.UserNotFoundException
import io.github.neronguyenvn.nerochat.user.service.AuthService
import io.github.neronguyenvn.nerochat.user.service.EmailVerificationService
import io.github.neronguyenvn.nerochat.user.service.PasswordResetService
import io.github.neronguyenvn.nerochat.user.service.ratelimiting.EmailRateLimitingService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/auth")
class AuthController(
    private val userService: AuthService,
    private val emailVerificationService: EmailVerificationService,
    private val passwordResetService: PasswordResetService,
    private val emailRateLimit: EmailRateLimitingService
) {

    /**
     * Creates a user and requests verification email delivery, returning the user response.
     * Errors from [AuthService.register] propagate.
     */
    @PostMapping("/register")
    @IpRateLimiting
    fun register(
        @Valid @RequestBody body: RegisterRequest
    ): UserDto {
        return userService.register(
            email = body.email,
            displayName = body.displayName,
            password = body.password
        ).asDto()
    }

    /**
     * Requests another verification email under the shared email rate limit.
     * Unknown emails are treated as successful attempts and also advance the limit. Returns 204
     * on success; other service and rate-limit errors propagate.
     */
    @PostMapping("/resend-verification")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @IpRateLimiting
    fun resendVerification(
        @Valid @RequestBody body: EmailRequest
    ) {
        emailRateLimit(body.email) {
            try {
                emailVerificationService.resendVerificationEmail(body.email)
            } catch (_: UserNotFoundException) {
                // Intentionally swallowed — never reveal whether the email is registered
            }
        }
    }

    /**
     * Returns the verified user's profile and authentication tokens.
     * Errors from [AuthService.login] propagate.
     */
    @PostMapping("/login")
    @IpRateLimiting
    fun login(
        @Valid @RequestBody body: LoginRequest
    ): AuthenticatedUserDto {
        return userService.login(
            email = body.email,
            password = body.password
        ).asDto()
    }

    /**
     * Returns the user and replacement tokens for a stored refresh token.
     * Errors from [AuthService.refreshToken] propagate.
     */
    @PostMapping("/refresh-token")
    @IpRateLimiting
    fun refreshToken(
        @Valid @RequestBody body: RefreshTokenRequest
    ): AuthenticatedUserDto {
        return userService.refreshToken(
            refreshToken = body.refreshToken
        ).asDto()
    }

    /**
     * Removes the supplied refresh token from storage; existing access tokens are not revoked.
     * Errors from [AuthService.logout] propagate.
     */
    @PostMapping("/logout")
    fun logout(
        @Valid @RequestBody body: RefreshTokenRequest
    ) {
        userService.logout(refreshToken = body.refreshToken)
    }

    /**
     * Consumes the supplied token and marks its user's email as verified.
     * Errors from [EmailVerificationService.verifyEmail] propagate.
     */
    // TODO: Keep authentication tokens out of request URLs.
    @GetMapping("/verify-email")
    fun verifyEmail(
        @RequestParam token: String
    ) {
        emailVerificationService.verifyEmail(token)
    }

    /**
     * Requests a password-reset email and returns 204, including for unknown email addresses.
     * Other errors from [PasswordResetService.requestPasswordReset] propagate.
     */
    @PostMapping("/forgot-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @IpRateLimiting
    fun forgotPassword(
        @Valid @RequestBody body: EmailRequest
    ) {
        emailRateLimit(body.email) {
            try {
                passwordResetService.requestPasswordReset(body.email)
            } catch (_: UserNotFoundException) {
                // Intentionally swallowed — never reveal whether the email is registered
            }
        }
    }

    /**
     * Resets the password and consumes the supplied token, revoking the user's refresh tokens.
     * Errors from [PasswordResetService.resetPassword] propagate.
     */
    @PostMapping("/reset-password")
    fun resetPassword(
        @Valid @RequestBody body: ResetPasswordRequest
    ) {
        passwordResetService.resetPassword(
            token = body.token,
            newPassword = body.newPassword
        )
    }

    /**
     * Changes the authenticated user's password after checking the current password.
     * Revokes their refresh tokens; errors from [PasswordResetService.changePassword] propagate.
     */
    @PostMapping("/change-password")
    fun changePassword(
        @Valid @RequestBody body: ChangePasswordRequest,
    ) {
        passwordResetService.changePassword(
            userId = requesterId,
            oldPassword = body.oldPassword,
            newPassword = body.newPassword
        )
    }
}
