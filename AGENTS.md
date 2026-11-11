# Agent Instructions — KMP-ChatBackend

## 1. Philosophy & Documentation Governance Gate

Before proposing any plan or writing code, read and follow:

1. **Global Agent Rules**: `~/.gemini/config/AGENTS.md` (Git sign-off/attribution, unified EGM + Archify lifecycle,
   post-implementation scaffolding pruning, and Archify/Chrome CLI commands).
2. **Project Philosophy**: `../KMP-ChatContract/docs/PHILOSOPHY.md` (especially Section 3 and Section 5 Mandatory Agent
   Planning Protocol).
3. **Unified Repository Documentation Index**: [`docs/README.md`](docs/README.md) (Single index for C4 Level 3
   `backend-components.archify.json`, Backend-internal Archify flows, OpenAPI schema, and EGM design specs in
   `docs/designs/`).
4. **Module Contract Invariant**: Every Gradle module maintains a machine-verifiable contract in its
   `<module>/README.md` (`Interface`, `Invariants`, `Side Effects`, `Verification`). Inspect a module's `README.md`
   before modifying it, and update it (plus root `README.md` if topology changes) in the same changeset.

---

## 2. Module Map & Architectural Invariants

### 2.1 Module Hierarchy
- `:app`: Bootstrap and composition root (Spring Boot entrypoint, global Security/JWT filter, OpenAPI configuration). Depends on `:common`, `:user`, `:chat`, `:notification`.
- `:common`: Cross-cutting domain primitives, JWT services, `ContractValidationAdvice`, AMQP event models (`UserEvent`, `ChatEvent`) and `EventPublisher`. Depends on `:contract:common`.
- `:user`: Account management, authentication, token issuance/refresh, email verification. Depends on `:common` and `:contract:auth`.
- `:chat`: Chat rooms, participants, messages, and raw WebSocket `/ws/chat` sessions. Depends on `:common` and `:contract:chat`.
- `:notification`: Asynchronous email notifications (Thymeleaf templates, JavaMailSender). Consumes `UserEvent` via RabbitMQ. Depends on `:common`.

### 2.2 Boundary Invariants & Hard Rules
1. **Domain Module Isolation**: Peer domain modules (`:user`, `:chat`, `:notification`) must **never** depend on or import each other. Cross-module communication is asynchronous via RabbitMQ domain events (`UserEvent`, `ChatEvent`).
2. **Service Layer Purity**: Service classes must return domain entities/models (not wire DTOs) and throw domain exceptions (never Spring Web `ResponseStatusException`). Mappings to wire DTOs occur at the `@RestController` layer.
3. **Contract SSOT**: Never hand-write duplicate wire DTOs, request/response models, or error classes. All wire types must derive from `io.github.neronguyenvn.nerochat.contract.*`.
4. **Error Handling Architecture**: Controller advices (`CommonExceptionHandler`, `AuthExceptionHandler`, `ChatExceptionHandler`) must construct `ApiErrorDto(error = <ServerError>, ...)` using flat `ErrorCode` strings.
5. **Anti-Enumeration Flows**: `POST /api/auth/resend-verification` and `POST /api/auth/forgot-password` return `204 No Content` and never return domain `AuthServerError` codes to prevent user enumeration.
6. **WebSocket Protocol**: `/ws/chat` uses raw Spring `TextWebSocketHandler` with `kotlinx.serialization` text frames (`classDiscriminator = "type"`); sessions must be wrapped in `ConcurrentWebSocketSessionDecorator`.

---

## 3. Verified Development & Verification Commands

| Task / Purpose | Tier | Verified Command |
|---|---|---|
| **Fast Inner-Loop Feedback** | Tier 1 (Iterative) | `./scripts/verify.sh fast` |
| **Strict Clean Verification Gate** | Tier 2 (Pre-Commit / Gate) | `./scripts/verify.sh clean` |
| Fast re-run of all module checks | Direct Gradle | `GRADLE_RO_DEP_CACHE=${HOME}/.gradle/caches ./gradlew check --gradle-user-home=/tmp/gradle-ro-home` |
| List runnable tasks | Utility | `GRADLE_RO_DEP_CACHE=${HOME}/.gradle/caches ./gradlew tasks --all --gradle-user-home=/tmp/gradle-ro-home --no-daemon` |

> [!IMPORTANT]
> **Verification Tier Policy**:
> - Use **Tier 1 (`fast`)** for rapid iterative feedback during development loops.
> - **MUST** run and pass **Tier 2 (`clean`)** before committing any changes or flipping any flow spec from `[PLANNED]`
    to `[AS-IS]`.
