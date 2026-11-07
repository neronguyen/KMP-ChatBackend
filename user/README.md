# user

## Overview
User account lifecycle management, credential authentication, refresh token rotation, email verification, password resets, and Redis-backed rate limiting.

## Interface
- `[AuthController.kt:AuthController]`: REST endpoints for `/api/auth` (`register`, `login`, `refresh-token`, `logout`, `verify-email`, `resend-verification`, `forgot-password`, `reset-password`, `change-password`).
- `[AuthService.kt:AuthService]`: Registration, credential verification, and token rotation business logic.
- `[EmailVerificationService.kt:EmailVerificationService]`: Token issuance and verification handling for email confirmation.
- `[PasswordResetService.kt:PasswordResetService]`: Password reset token lifecycle and password mutation.
- `[IpRateLimitingService.kt:IpRateLimitingService]`: Redis-backed distributed IP rate limiting for authentication endpoints.
- `[EmailRateLimitingService.kt:EmailRateLimitingService]`: Redis-backed distributed email rate limiting to mitigate brute-force attempts.
- `[AuthExceptionHandler.kt:AuthExceptionHandler]`: Exception handler mapping domain exceptions (`InvalidCredentialsException`, `EmailNotVerifiedException`, etc.) to `ApiErrorDto(error = AuthServerError.*)`.

## Invariants
- Depends on `:common` and `:contract:auth`. Never imports `:chat` or `:notification`.
- `POST /api/auth/resend-verification` and `POST /api/auth/forgot-password` return `204 No Content` without leaking whether the user exists (anti-enumeration).
- Domain exceptions are mapped to wire codes in `AuthExceptionHandler`, never thrown as Spring Web `ResponseStatusException`.
- Passwords must be hashed using BCrypt (`PasswordEncoderConfig.kt`).

## Side Effects
- Reads/writes PostgreSQL tables: `users`, `refresh_tokens`, `auth_tokens`.
- Acquires Redis distributed rate-limiting buckets and keys.
- Publishes `UserEvent.Created`, `UserEvent.Verified`, `UserEvent.RequestResendVerification`, `UserEvent.RequestResetPassword` via `EventPublisher`.

## Verification
- Run: `./gradlew :user:test --rerun-tasks`
