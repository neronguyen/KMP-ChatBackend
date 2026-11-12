# user

## Overview

User account lifecycle management, credential authentication, refresh token rotation, 6-digit OTP email verification,
password resets, and Redis-backed rate limiting.

## Interface
- `[AuthController.kt:AuthController]`: REST endpoints for `/api/auth` (`register`, `login`, `refresh-token`, `logout`, `verify-email`, `resend-verification`, `forgot-password`, `reset-password`, `change-password`).
- `[AuthService.kt:AuthService]`: Registration, credential verification, and token rotation business logic.
- `[RedissonOtpService.kt:RedissonOtpService]`: Redis-backed 6-digit numeric OTP generation, peppered SHA-256 hashing,
  attempt throttling, and brute-force lockout management.
- `[EmailVerificationService.kt:EmailVerificationService]`: 6-digit OTP verification and user verification state
  handling for email confirmation.
- `[PasswordResetService.kt:PasswordResetService]`: 6-digit OTP verification, password mutation, and global refresh
  token revocation.
- `[IpRateLimitingService.kt:IpRateLimitingService]`: Redis-backed distributed IP rate limiting for authentication endpoints.
- `[EmailRateLimitingService.kt:EmailRateLimitingService]`: Redis-backed distributed email rate limiting to mitigate brute-force attempts.
- `[AuthExceptionHandler.kt:AuthExceptionHandler]`: Exception handler mapping domain exceptions
  (`InvalidCredentialsException`, `EmailNotVerifiedException`, `InvalidOtpException`, `OtpExpiredException`,
  `MaxAttemptsExceededException`, etc.) to `ApiErrorDto(error = AuthServerError.*)`.

## Invariants
- Depends on `:common` and `:contract:auth`. Never imports `:chat` or `:notification`.
- `POST /api/auth/resend-verification` and `POST /api/auth/forgot-password` return `204 No Content` without leaking whether the user exists (anti-enumeration).
- Domain exceptions are mapped to wire codes in `AuthExceptionHandler`, never thrown as Spring Web `ResponseStatusException`.
- Passwords must be hashed using BCrypt (`PasswordEncoderConfig.kt`).
- OTP codes and attempt state are managed purely in Redis via Redisson with zero relational database persistence.

## Side Effects

- Reads/writes PostgreSQL tables: `users`, `refresh_tokens`.
- Acquires Redis distributed rate-limiting buckets, lock leases, and OTP lifecycle keys (`auth:otp:*`).
- Publishes `UserEvent.Created`, `UserEvent.Verified`, `UserEvent.RequestResendVerification`, `UserEvent.RequestResetPassword` via `EventPublisher`.

## Verification
- Run: `./gradlew :user:test --rerun-tasks`
