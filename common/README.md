# common

## Overview
Cross-cutting backend domain primitives, shared security/JWT token services, contract validation advice, global exception handling, and RabbitMQ AMQP event infrastructure.

## Interface
- `[JwtService.kt:JwtService]`: Access and refresh token generation, claims extraction, and signature validation.
- `[ContractValidationAdvice.kt:ContractValidationAdvice]`: Automatic validation binder triggering `Validatable.validate()` on incoming `@RequestBody` DTOs.
- `[CommonExceptionHandler.kt:CommonExceptionHandler]`: Global Spring `@RestControllerAdvice` mapping validation, standard HTTP, and unexpected exceptions to `ApiErrorDto(error = CommonServerError.*)`.
- `[EventPublisher.kt:EventPublisher]`: Asynchronous AMQP event publisher dispatching domain events to RabbitMQ topic exchanges.
- `[RabbitMqConfig.kt:RabbitMqConfig]`: Topic exchanges, message converters, and virtual host configuration.
- `[MessageQueue.kt:MessageQueue]`: Queue constants for RabbitMQ service consumers.
- `[UserEvent.kt:UserEvent]`: Sealed domain events (`Created`, `Verified`, `RequestResendVerification`, `RequestResetPassword`).
- `[ChatEvent.kt:ChatEvent]`: Sealed domain events (`NewMessage`).
- `[UserId.kt:UserId]`, `[ChatRoomId.kt:ChatRoomId]`, `[ChatMessageId.kt:ChatMessageId]`: Strongly-typed inline value class identifiers.

## Invariants
- Depends on `:contract:common` via `api(libs.contract.common)`.
- Peer domain modules (`:user`, `:chat`, `:notification`) depend on `:common`, but `:common` must never depend on any peer module.
- All AMQP events serialize as JSON using `KotlinSerializationJsonAmqpMessageConverter`.

## Side Effects
- Publishes messages to RabbitMQ topic exchanges (`chat.events`, `user.events`).

## Verification
- Run: `./gradlew :common:test --rerun-tasks`
