# Agent Instructions — KMP-ChatBackend

## 1. Philosophy & Governance Gate
Before proposing any plan or writing code, read and strictly follow:
1. `../KMP-ChatContract/docs/PHILOSOPHY.md` (or `https://github.com/neronguyen/KMP-ChatContract/blob/main/docs/PHILOSOPHY.md` if sibling directory is unavailable), especially Section 3 and Section 5.
2. Architecture specifications:
   - Level 3 Backend Components: `docs/architecture/specifications/backend-components.archify.json` (Viewer: `docs/architecture/interactive/c4-backend-components.html`)
3. Baseline Audit Snapshot: `docs/audit/2026-11-baseline-backend.md`

Every implementation plan must satisfy the **Mandatory Agent Planning Protocol** defined in `PHILOSOPHY.md` Section 5 before execution (read it; it is not restated here).

### 1.1 Design-First Flow Gate (Tooling Binding for Protocol Step 3)
To satisfy Step 3 (`To-Be Flow & Lifecycle Spec`) of the Planning Protocol:
1. Cross-repo wire flows are defined in `:contract` (`docs/architecture/specifications/flows/`). Consult `docs/architecture/flows/README.md`. Backend-internal flows are stored in `docs/architecture/specifications/flows/<name>.<type>.archify.json` (`<type>` = `sequence` | `lifecycle`) and registered in `docs/architecture/flows/README.md`.
2. Mark `meta.title` and the registry row `[PLANNED]` before writing code.
3. **Completion Invariant (No Dangling `[PLANNED]`)**: After Step 4 verification passes, flip `[PLANNED]` → `[AS-IS]` in both the `.archify.json` spec and `docs/architecture/flows/README.md`, re-render interactive HTML and image assets, and verify that no in-scope flow remains `[PLANNED]`.
4. Render and verify the interactive HTML diagram via:
   `node ~/.gemini/config/skills/archify/bin/archify.mjs deliver <type> <input.json> <output.html> --quality showcase --json && node ~/.gemini/config/skills/archify/bin/archify.mjs check <output.html> --json`
5. **Static Image Assets**: For diagrams embedded in `README.md` or markdown documentation, export and update dark and light static image assets in `docs/architecture/assets/<name>.png` and `docs/architecture/assets/<name>.light.png`.

### 1.2 Module Contracts & Design Registry (EGM)
1. **Design Registry**: Approved technical design documents live in `docs/designs/` and are indexed in `docs/designs/SITEMAP.md`. In an execution session, follow the approved design's implementation plan and verify any impacted flows against Section 1.1.
2. **Module Contract Invariant**: Every Gradle module maintains a machine-verifiable contract in its `README.md` (`Interface`, `Invariants`, `Side Effects`, `Verification`). Before modifying a module, inspect its `README.md`. Any change to public symbols, invariants, side effects, or dependencies must update that module's `README.md` (and parent blueprint if boundaries change) in the same changeset.

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

All agent verification must be performed using these verified-green commands:

| Task / Purpose | Tier | Verified Command |
|---|---|---|
| **Fast Inner-Loop Feedback** | Tier 1 (Iterative) | `./scripts/verify.sh fast` |
| **Strict Clean Verification Gate** | Tier 2 (Pre-Commit / Gate) | `./scripts/verify.sh clean` |
| Fast re-run of all module tests | Direct Gradle | `GRADLE_RO_DEP_CACHE=${HOME}/.gradle/caches ./gradlew :common:test :user:test :chat:test :app:test :notification:test --gradle-user-home=/tmp/gradle-ro-home --offline` |
| List runnable tasks | Utility | `GRADLE_RO_DEP_CACHE=${HOME}/.gradle/caches ./gradlew tasks --all --gradle-user-home=/tmp/gradle-ro-home --offline --no-daemon` |

> [!IMPORTANT]
> **Verification Tier Policy**:
> - Agents may use **Tier 1 (`fast`)** for rapid iterative feedback during development loops.
> - Agents **MUST** run and pass **Tier 2 (`clean`)** before committing any changes or flipping any flow spec from `[PLANNED]` to `[AS-IS]`.

*Note: In sandboxed environments with daemon lock contention, using `--gradle-user-home=/tmp/gradle-ro-home` with `GRADLE_RO_DEP_CACHE` avoids lock collisions with host daemons. `./scripts/verify.sh` automatically manages symlinks and paths.*


