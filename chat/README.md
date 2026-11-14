# chat

## Overview
Chat room and message management, direct/group room provisioning, real-time authenticated WebSocket `/ws/chat` sessions, and Redis message slice caching.

## Interface
- `[ChatRoomController.kt:ChatRoomController]`: REST endpoints for room queries, room creation, participants addition, room leave, and message history (`GET /api/chat/rooms`, `GET /api/chat/rooms/{roomId}`, `POST /api/chat/rooms/direct`, `POST /api/chat/rooms/group`, `POST /api/chat/rooms/{roomId}/add`, `DELETE /api/chat/rooms/{roomId}/leave`, `GET /api/chat/rooms/{roomId}/messages`).
- `[ChatMessageController.kt:ChatMessageController]`: REST endpoint for message deletion (`DELETE /api/chat/messages/{messageId}`).
- `[ChatParticipantController.kt:ChatParticipantController]`: REST endpoint for participant search by email (`GET /api/chat/participants`).
- `[ChatWebSocketHandler.kt:ChatWebSocketHandler]`: Authenticated WebSocket connection handler at `/ws/chat` decoding `WsClientMessage` and broadcasting `WsServerEvent` using `ContractJson`.
- `[ChatRoomService.kt:ChatRoomService]`: Room creation, membership verification, and participant management.
- `[ChatMessageService.kt:ChatMessageService]`: Message persistence, idempotent deduplication, and Redis slice caching.
- `[ChatUserEventListener.kt:ChatUserEventListener]`: RabbitMQ AMQP listener provisioning participant records when a user verifies their email.
- `[ChatExceptionHandler.kt:ChatExceptionHandler]`: Exception handler mapping chat domain exceptions to `ApiErrorDto(error = ChatServerError.*)`.

## Invariants
- Depends on `:common` and `:contract:chat`. Never imports `:user` or `:notification`.
- `/ws/chat` handshakes validate JWT access tokens from `Authorization: Bearer <token>` and reject invalid tokens with `POLICY_VIOLATION (1008)`.
- WebSocket sessions are wrapped in `ConcurrentWebSocketSessionDecorator` to prevent concurrent write corruption.
- Messages must be persisted to PostgreSQL before WebSocket broadcast.

## Side Effects
- Reads/writes PostgreSQL tables: `chat_rooms`, `chat_participants`, `chat_messages`.
- Reads/writes Redis cache for recent message slices (`messages` cache).
- Consumes `UserEvent.Verified` from RabbitMQ.
- Publishes `ChatEvent.NewMessage` to RabbitMQ.

## Verification
- Run: `./gradlew :chat:test --rerun-tasks`
