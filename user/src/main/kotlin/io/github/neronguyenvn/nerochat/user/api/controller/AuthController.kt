package io.github.neronguyenvn.nerochat.user.api.controller

import io.github.neronguyenvn.nerochat.api.annotation.ApiErrors
import io.github.neronguyenvn.nerochat.api.util.requesterId
import io.github.neronguyenvn.nerochat.contract.auth.AuthenticatedUserDto
import io.github.neronguyenvn.nerochat.contract.auth.UserDto
import io.github.neronguyenvn.nerochat.contract.auth.error.AuthServerError
import io.github.neronguyenvn.nerochat.contract.error.CommonServerError
import io.github.neronguyenvn.nerochat.domain.exception.InvalidTokenException
import io.github.neronguyenvn.nerochat.user.api.config.IpRateLimiting
import io.github.neronguyenvn.nerochat.user.api.mapper.asDto
import io.github.neronguyenvn.nerochat.contract.auth.ChangePasswordRequest
import io.github.neronguyenvn.nerochat.contract.auth.EmailRequest
import io.github.neronguyenvn.nerochat.contract.auth.LoginRequest
import io.github.neronguyenvn.nerochat.contract.auth.RefreshTokenRequest
import io.github.neronguyenvn.nerochat.contract.auth.RegisterRequest
import io.github.neronguyenvn.nerochat.contract.auth.ResetPasswordRequest
import io.github.neronguyenvn.nerochat.user.domain.exception.EmailNotVerifiedException
import io.github.neronguyenvn.nerochat.user.domain.exception.InvalidCredentialsException
import io.github.neronguyenvn.nerochat.user.domain.exception.SamePasswordException
import io.github.neronguyenvn.nerochat.user.domain.exception.UserAlreadyExistsException
import io.github.neronguyenvn.nerochat.user.domain.exception.UserNotFoundException
import io.github.neronguyenvn.nerochat.user.domain.exception.WrongPasswordException
import io.github.neronguyenvn.nerochat.user.service.AuthService
import io.github.neronguyenvn.nerochat.user.service.EmailVerificationService
import io.github.neronguyenvn.nerochat.user.service.PasswordResetService
import io.github.neronguyenvn.nerochat.user.service.ratelimiting.EmailRateLimitingService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@Tag(name = "Auth", description = "Authentication & registration endpoints")
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
    @Operation(summary = "Register user", description = "Creates a new user and sends verification email")
    @PostMapping("/register")
    @IpRateLimiting
    @ApiErrors(
        AuthServerError.UserAlreadyExists::class,
        CommonServerError.RateLimitExceeded::class,
        CommonServerError.ValidationFailed::class,
    )
    @Throws(UserAlreadyExistsException::class)
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
    @Operation(summary = "Resend verification email", description = "Requests another verification email under rate limit")
    @PostMapping("/resend-verification")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @IpRateLimiting
    @ApiErrors(
        CommonServerError.RateLimitExceeded::class,
        CommonServerError.ValidationFailed::class,
    )
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
    @Operation(summary = "Login user", description = "Authenticates credentials and returns JWT tokens")
    @PostMapping("/login")
    @IpRateLimiting
    @ApiErrors(
        AuthServerError.InvalidCredentials::class,
        AuthServerError.EmailNotVerified::class,
        CommonServerError.RateLimitExceeded::class,
        CommonServerError.ValidationFailed::class,
    )
    @Throws(InvalidCredentialsException::class, EmailNotVerifiedException::class)
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
    @Operation(summary = "Refresh access token", description = "Issues a new access token using a refresh token")
    @PostMapping("/refresh-token")
    @IpRateLimiting
    @ApiErrors(
        AuthServerError.InvalidToken::class,
        AuthServerError.UserNotFound::class,
        CommonServerError.RateLimitExceeded::class,
        CommonServerError.ValidationFailed::class,
    )
    @Throws(InvalidTokenException::class, UserNotFoundException::class)
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
    @Operation(summary = "Logout user", description = "Invalidates the refresh token")
    @PostMapping("/logout")
    @ApiErrors(
        AuthServerError.InvalidToken::class,
        CommonServerError.ValidationFailed::class,
    )
    @Throws(InvalidTokenException::class)
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
    @Operation(summary = "Verify email", description = "Verifies email address using verification token")
    @GetMapping("/verify-email")
    @ApiErrors(AuthServerError.InvalidToken::class)
    @Throws(InvalidTokenException::class)
    fun verifyEmail(
        @RequestParam token: String
    ) {
        emailVerificationService.verifyEmail(token)
    }

    /**
     * Requests a password-reset email and returns 204, including for unknown email addresses.
     * Other errors from [PasswordResetService.requestPasswordReset] propagate.
     */
    @Operation(summary = "Forgot password", description = "Requests password reset email")
    @PostMapping("/forgot-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @IpRateLimiting
    @ApiErrors(
        CommonServerError.RateLimitExceeded::class,
        CommonServerError.ValidationFailed::class,
    )
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
    @Operation(summary = "Reset password", description = "Resets password using reset token")
    @PostMapping("/reset-password")
    @IpRateLimiting
    @ApiErrors(
        AuthServerError.InvalidToken::class,
        AuthServerError.SamePassword::class,
        CommonServerError.RateLimitExceeded::class,
        CommonServerError.ValidationFailed::class,
    )
    @Throws(InvalidTokenException::class, SamePasswordException::class)
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
    @Operation(summary = "Change password", description = "Changes authenticated user password")
    @PostMapping("/change-password")
    @ApiErrors(
        CommonServerError.Unauthorized::class,
        AuthServerError.WrongPassword::class,
        AuthServerError.SamePassword::class,
        AuthServerError.UserNotFound::class,
        CommonServerError.ValidationFailed::class,
    )
    @Throws(
        WrongPasswordException::class,
        SamePasswordException::class,
        UserNotFoundException::class
    )
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
