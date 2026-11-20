# common

## Overview
Cross-cutting backend domain primitives, shared security/JWT token services, contract validation advice, global exception handling, and RabbitMQ AMQP event infrastructure.

## Interface
- `[JwtService.kt:JwtService]`: Access and refresh token generation, claims extraction, and signature validation.
- `[ContractValidationAdvice.kt:ContractValidationAdvice]` & `[ContractValidationAdvice.kt:ContractRequestValidator]`: Automatic validation binder triggering `Validatable.validate()` on incoming `@RequestBody` DTOs.
- `[CommonExceptionHandler.kt:CommonExceptionHandler]`: Global Spring `@RestControllerAdvice` mapping validation, standard HTTP, and unexpected exceptions to `ApiErrorDto(error = CommonServerError.*)`.
- `[ApiErrors.kt:ApiErrors]`: Controller method annotation declaring expected `ServerError` contracts for OpenAPI export.
- `[SecurityExtensions.kt:requesterId]`, `[EmailValidation.kt]`, `[UuidValidation.kt]`, `[EmailNormalization.kt:normalizeEmail]`, `[CacheNames.kt:CacheNames]`: Shared controller security, email/UUID validation/normalization helpers, and cache name constants.
- `[EventPublisher.kt:EventPublisher]`: Asynchronous AMQP event publisher dispatching domain events to RabbitMQ topic exchanges.
- `[RabbitMqConfig.kt:RabbitMqConfig]`: Topic exchanges, durable queues, bindings, `RabbitTemplate`, and `KotlinSerializationJsonAmqpMessageConverter` configuration.
- `[MessageQueue.kt:MessageQueue]`: Queue constants for RabbitMQ service consumers.
- `[UserEvent.kt:UserEvent]`: Sealed domain events (`Created`, `Verified`, `RequestResendVerification`, `RequestResetPassword`).
- `[ChatEvent.kt:ChatEvent]`: Sealed domain events (`NewMessage`).
- `[UserId.kt:UserId]`, `[ChatRoomId.kt:ChatRoomId]`, `[ChatMessageId.kt:ChatMessageId]`: Strongly-typed inline value class identifiers.

## Invariants
- Exposes `:contract:common`, `:validation:common`, and Jakarta Swagger annotations via `api(...)`.
- Peer domain modules (`:user`, `:chat`, `:notification`) depend on `:common`, but `:common` must never depend on any peer module.
- All AMQP events serialize as JSON using `KotlinSerializationJsonAmqpMessageConverter`.

## Side Effects
- Publishes messages to RabbitMQ topic exchanges (`chat.events`, `user.events`).

## Verification
- Run: `./gradlew :common:test --rerun-tasks`
