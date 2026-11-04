# Backend Architecture Flows

This directory tracks Backend-specific state and domain event flows (e.g., RabbitMQ domain event choreography, distributed rate limiting, and JPA persistence transactions).

## Cross-Repository Flows
Shared client-server communication flows (WebSocket lifecycle, token refresh, optimistic message transmission) are specified in the Contract repository as the Single Source of Truth (SSOT):
- **Master Flow Index**: [Contract Flow Registry](../../../KMP-ChatContract/docs/architecture/flows/README.md)
- **WebSocket Connection & Backoff**: `ws-lifecycle.sequence.archify.json`
- **Single-Flight Token Refresh**: `token-refresh.sequence.archify.json`
- **Optimistic Send & Idempotency**: `optimistic-send.sequence.archify.json`

## Backend-Internal Flows (Track C+)

| Flow | Type | Status | Specification | Asserting Test Suite |
|---|---|---|---|---|
| *RabbitMQ User & Chat Domain Event Pipeline* | `dataflow` | `[PLANNED]` | `docs/architecture/specifications/flows/rabbitmq-events.dataflow.archify.json` (Track C) | `:common:test`, `:notification:test` |
| *Distributed Rate Limiting via Redisson* | `sequence` | `[PLANNED]` | `docs/architecture/specifications/flows/rate-limiting.sequence.archify.json` (Track C) | `:user:test` |
