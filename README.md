# KMP-ChatBackend

Modular monolith backend for the end-to-end chat project — handling stateless JWT authentication, distributed rate
limiting, room/message persistence, real-time WebSocket sessions, and asynchronous domain events.

> **Architecture & Philosophy**
> - Read the [Chat Project Philosophy](../KMP-ChatContract/docs/PHILOSOPHY.md)
    ([GitHub](https://github.com/neronguyen/KMP-ChatContract/blob/main/docs/PHILOSOPHY.md)) for the architectural
    principles and AI agent governance rules governing this repository.
> - **Medium
    Series**: [Part 1 — End-to-End Type Safety: Sharing Pure Kotlin Contracts Between Android and Spring Boot](https://medium.com/@neronguyen/end-to-end-type-safety-sharing-pure-kotlin-contracts-between-android-and-spring-boot-part-1-8c45e3ab6720)

## Related Repositories

| Repository                                                                             | Role                                                                    | C4 Architecture Level                                   | Tech Stack                                                                    |
|:---------------------------------------------------------------------------------------|:------------------------------------------------------------------------|:--------------------------------------------------------|:------------------------------------------------------------------------------|
| **[KMP-ChatContract](https://github.com/neronguyen/KMP-ChatContract)**                 | Compile-time wire schema, error contracts, and shared validators        | **Level 1** (System Context) & **Level 2** (Containers) | Pure Kotlin 2.4, `kotlinx.serialization`, `kotlinx-datetime`                  |
| **[KMP-ChatBackend](https://github.com/neronguyen/KMP-NeroChatBackend)** *(this repo)* | Modular monolith REST & WebSocket server                                | **Level 3** (Backend Components)                        | Kotlin, Spring Boot 4, PostgreSQL, Redis (Redisson), RabbitMQ                 |
| **[Android-ChatApp](https://github.com/neronguyen/Android-NeroChatApp)**               | Native Android client with offline-first persistence and real-time sync | **Level 3** (Android Components)                        | Jetpack Compose, Navigation 3, Retrofit, OkHttp, Room, DataStore, Hilt, Arrow |

---

## Backend Component Architecture (C4 Level 3)

> **Interactive Architecture Viewers**:
> - **Level 3 — Backend Server Components (Local HTML)**: [
    `docs/architecture/interactive/c4-backend-components.html`](docs/architecture/interactive/c4-backend-components.html)
> - **Level 1 & Level 2 — System Context & Containers**: [
    `KMP-ChatContract/docs/architecture/`](https://github.com/neronguyen/KMP-ChatContract/tree/main/docs/architecture)

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/architecture/assets/BackendComponents.png">
  <source media="(prefers-color-scheme: light)" srcset="docs/architecture/assets/BackendComponents.light.png">
  <img alt="Backend Server Components (C4 Level 3)" src="docs/architecture/assets/BackendComponents.png">
</picture>

### Ingress & Security Boundary

- **REST Ingress (`/api/**`)**: `JwtAuthenticationFilter` authenticates Bearer access tokens into the Spring Security
  context, while `ContractValidationAdvice` automatically invokes `Validatable.validate()` from `KMP-ChatContract` on
  incoming `@RequestBody` DTOs before controller execution.
- **WebSocket Ingress (`/ws/chat`)**: Because `/ws/chat` is upgraded via raw Spring WebSocket (`permitAll()` at the HTTP
  filter layer), `ChatWebSocketHandler.afterConnectionEstablished` extracts the `Authorization: Bearer <token>` header
  during the HTTP Upgrade handshake, validates the JWT via `JwtService.validateAccessToken`, and closes unauthenticated
  sockets immediately (`POLICY_VIOLATION 1008`).

### Modules

- `:app`: Application bootstrap (`ChatApp`), Spring Security filter chain (`SecurityConfig`, `JwtAuthenticationFilter`,
  `TrustedProxyFilter`), Redisson cache configuration (`RedisConfig`), and OpenAPI/Postman export tooling.
- `:common`: Cross-cutting domain primitives (`UserId`, `ChatRoomId`, `ChatMessageId`), JWT token service
  (`JwtService`), shared contract validation binder (`ContractValidationAdvice`), global exception handling
  (`CommonExceptionHandler`), and RabbitMQ AMQP topic exchanges (`RabbitMqConfig`).
- `:user`: Registration, login, refresh-token rotation, email verification, password reset (`AuthService`,
  `EmailVerificationService`, `PasswordResetService`), and Redis-backed IP/email rate limiting (`IpRateLimitingService`,
  `EmailRateLimitingService`).
- `:chat`: Chat room and message management (`ChatRoomService`, `ChatMessageService`), authenticated WebSocket session
  handler (`ChatWebSocketHandler` at `/ws/chat`), and RabbitMQ listener (`ChatUserEventListener`) that provisions chat
  participants when users verify their email.
- `:notification`: Asynchronous RabbitMQ consumer (`NotificationUserEventListener`) and SMTP email delivery
  (`EmailService`, Thymeleaf templates) for verification and password-reset flows.
- `build-logic`: Gradle convention plugins standardizing Kotlin, Spring Boot, and serialization configurations across
  modules.

---

## Contract Integration

1. **Request Validation**: Incoming request DTOs implementing `Validatable` from `KMP-ChatContract` are automatically
   validated by `ContractValidationAdvice` before reaching controller handlers.
2. **Error Enforcement**: Domain exceptions in `:user`, `:chat`, and `:common` map deterministically to `ApiErrorDto`
   payloads carrying sealed `AuthServerError`, `ChatServerError`, or `CommonServerError` codes.
3. **WebSocket Wire Protocol**: `ChatWebSocketHandler` decodes incoming frames as `WsClientMessage` and broadcasts
   `WsServerEvent` payloads serialized with `kotlinx.serialization`.

---

## Tech Stack

- **Runtime & Framework**: Kotlin 2.4, Spring Boot 4.0 (Spring Web, WebSocket, Security, Data JPA, AMQP, Mail,
  Thymeleaf)
- **Serialization**: `kotlinx.serialization-json` (REST, WebSocket frames, and RabbitMQ AMQP messages)
- **Persistence & Caching**: PostgreSQL (HikariCP, JPA), Redis via Redisson (distributed locks, rate limiters, 30-minute
  message slice cache)
- **Messaging**: RabbitMQ (AMQP topic exchanges for `UserEvent` and `ChatEvent`)
- **API Documentation**: SpringDoc OpenAPI (`docs/openapi.json`) and generated Postman collection
  (`docs/nerochat.postman_collection.json`)

---

## Local Development & Verification

### Composite Build with Local Contract

When `KMP-ChatContract` is cloned in the sibling directory (`../KMP-ChatContract`), Gradle automatically substitutes all
`io.github.neronguyenvn.nerochat.contract:*` and `validation:*` dependencies with local project modules. To force remote
artifact resolution instead, pass `-PuseLocalContract=false`.

### Configuration

Provide environment variables or a root `local.properties` file with:

- `POSTGRES_URL`, `POSTGRES_USERNAME`, `POSTGRES_PASSWORD`
- `REDIS_HOST`, `REDIS_PORT`, `REDIS_PASSWORD`
- `RABBITMQ_HOST`, `RABBITMQ_USERNAME`, `RABBITMQ_PASSWORD`, `RABBITMQ_VHOST`
- `SMTP_HOST`, `SMTP_PORT`, `SMTP_USERNAME`, `SMTP_PASSWORD`, `SMTP_SENDER`
- `JWT_SECRET_BASE64`

### Run Tests & Start Server

```bash
# Run all unit and integration tests
./gradlew test

# Start the backend server locally
./gradlew :app:bootRun
```
