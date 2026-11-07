# notification

## Overview
Asynchronous email notification delivery consuming user lifecycle events from RabbitMQ and dispatching transactional HTML emails via SMTP and Thymeleaf.

## Interface
- `[NotificationUserEventListener.kt:NotificationUserEventListener]`: RabbitMQ AMQP listener consuming `UserEvent` to trigger verification and password reset emails.
- `[EmailService.kt:EmailService]`: Mail dispatch service wrapping Spring `JavaMailSender` and MIME message construction.
- `[EmailTemplateService.kt:EmailTemplateService]`: Thymeleaf template engine rendering email bodies (`account-verification.html`, `reset-password.html`).

## Invariants
- Depends on `:common`. Never depends directly on `:user` or `:chat`.
- Consumes events asynchronously via RabbitMQ AMQP topics; zero synchronous HTTP endpoints exposed.
- Email dispatch failures are logged and rethrown so errors propagate to the AMQP message listener container.

## Side Effects
- Dispatches transactional emails via external SMTP server (`JavaMailSender`).
- Consumes messages from RabbitMQ user event queue.

## Verification
- Run: `./gradlew :notification:test --rerun-tasks`
