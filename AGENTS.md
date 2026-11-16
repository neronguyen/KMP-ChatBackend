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
3. **Contract SSOT**: Never hand-write duplicate wire DTOs, request/response models, or error classes. All wire types
   must derive from `io.github.neronguyenvn.nerochat.contract.*`. Likewise, do not duplicate standalone DTO or
   `ErrorCode` golden wire serialization tests—`KMP-ChatContract` owns pure contract wire-format tests; backend wire
   tests (`BackendGoldenWireTest`) must strictly verify Spring MVC / WebSocket / `ContractJson` bean wiring.
4. **Error Handling Architecture**: Controller advices (`CommonExceptionHandler`, `AuthExceptionHandler`, `ChatExceptionHandler`) must construct `ApiErrorDto(error = <ServerError>, ...)` using flat `ErrorCode` strings.
5. **Anti-Enumeration Flows**: `POST /api/auth/resend-verification` and `POST /api/auth/forgot-password` return `204 No Content` and never return domain `AuthServerError` codes to prevent user enumeration.
6. **WebSocket Protocol**: `/ws/chat` uses raw Spring `TextWebSocketHandler` with `kotlinx.serialization` text frames (`classDiscriminator = "type"`); sessions must be wrapped in `ConcurrentWebSocketSessionDecorator`.

---

## 3. Testing Pyramid & Verification Commands

Following the [Scalable Testing Strategy](https://developer.android.com/training/testing/fundamentals/strategies) shared
across the NeroChat repositories, tests are organized into a 5-layer pyramid mapped across 3 execution tiers (always
target the lowest pyramid layer that provides the required feedback):

| Pyramid Layer                                                                    | Scope & Test Suites in `KMP-ChatBackend`                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                       | Execution Tier & Cadence                                                                                                                                                     | Command / CI Workflow                                                                                                                                                                                                                                           |
|:---------------------------------------------------------------------------------|:-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|:-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------|:----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **1. Unit**<br>**2. Component**<br>**3. Feature**<br>**4. Application (Static)** | • **Unit**: Pure JVM logic, services, JWT, validators, controllers, exception handlers, AMQP listeners (`*ServiceTest`, `*ControllerTest`, `ChatWebSocketHandlerTest`, `JwtServiceTest`)<br>• **Component**: H2 `MODE=PostgreSQL` JPA & transaction slices (`RefreshTokenRepositoryTest`, `ChatMessageRepositoryTest`, `ChatRoomServiceTransactionTest`) and Spring Web/Security/Contract MockMvc slices (`SecurityAndValidationIntegrationTest`, `BackendGoldenWireTest`)<br>• **Feature**: Full `ChatApp` context + Testcontainers (`PostgreSQL`, `Redis`, `RabbitMQ`; `@Tag("integration")`, `@Testcontainers(disabledWithoutDocker = true)`): `DirectRoomConcurrencyIntegrationTest`, `RedissonOtpIntegrationTest`, `UserEventAmqpIntegrationTest`<br>• **Application (Static)**: `ArchitectureKonsistTest`, diagram freshness (`sync-archify.sh --check`), OpenAPI/Postman export (`OpenApiExportTest`, `@Tag("export")`) | **Tier 1 (Iterative / Local)**: Affected modules on JVM (~3–10s)<br>**Tier 2 (Pre-Merge Gate)**: Full clean suite (`check --rerun-tasks`) + drift/diagram checks on every PR | • Tier 1: `./scripts/verify.sh`<br>• Tier 2: `./scripts/verify.sh clean`<br>• Feature (containers only): `./gradlew :app:integrationTest --rerun-tasks`<br>• CI: [`.github/workflows/ci.yml`](.github/workflows/ci.yml)                                         |
| **5. End-to-End (E2E)**                                                          | • Full-stack Spring Boot (`dev,e2e`) + Docker Compose (`postgres`, `redis`, `rabbitmq`, `mailpit`) + Android AVD client (`DirectChatE2ETest`, `OtpRecoveryE2ETest`)                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                            | **Tier 3 (Milestone / Nightly)**: Real local/CI Docker stack + AVD                                                                                                           | • Infra & Boot: `docker compose -f docker-compose.e2e.yml up -d --wait && ./gradlew :app:bootRun --args='--spring.profiles.active=dev,e2e'`<br>• Cross-repo runner: `../Android-ChatApp/scripts/e2e/verify.sh` (`../Android-ChatApp/.github/workflows/e2e.yml`) |

### Quick Command Reference

| Task / Purpose                      | Tier                       | Verified Command                                                                                                                                                                               |
|-------------------------------------|----------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **Fast Selective Feedback**         | Tier 1 (Iterative / Local) | `./scripts/verify.sh` *(Auto-detects modified modules via Git, reuses daemon & build cache)*                                                                                                   |
| **Selective Diagram Sync**          | Tier 1 (Architecture Doc)  | `./scripts/sync-archify.sh` *(Auto-detects modified .archify.json, delivers HTML, checks layout, exports dual-theme PNGs)*                                                                     |
| **Verify Diagram Freshness**        | Tier 2 (CI / Pre-Push)     | `./scripts/sync-archify.sh --check`                                                                                                                                                            |
| **Strict Clean Verification Gate**  | Tier 2 (CI / Pre-Merge)    | `./scripts/verify.sh clean` *(Full rerun of `check` across all modules + git drift check; enforced by `.github/workflows/ci.yml`)*                                                             |
| **Testcontainers Integration Only** | Tier 2 (Targeted / Docker) | `./gradlew :app:integrationTest --rerun-tasks` *(Runs only `@Tag("integration")` suites against real Postgres, Redis, RabbitMQ containers; skipped in `:app:test` only when Docker is absent)* |
| **Tier 3 Local E2E Infra & Boot**   | Tier 3 (Local Full-Stack)  | `docker compose -f docker-compose.e2e.yml up -d --wait && ./gradlew :app:bootRun --args='--spring.profiles.active=dev,e2e'` *(Driven by `../Android-ChatApp/scripts/e2e/{verify,service}.sh`)* |
| Export OpenAPI & Postman Specs      | Documentation              | `./gradlew :app:exportApiDocs` *(Runs `@Tag("export")` `OpenApiExportTest` to update `docs/openapi.json` & `docs/nerochat.postman_collection.json`)*                                           |
| Fast re-run of all module checks    | Direct Gradle              | `GRADLE_RO_DEP_CACHE=${HOME}/.gradle/caches ./gradlew check --gradle-user-home=/tmp/gradle-ro-home`                                                                                            |
| List runnable tasks                 | Utility                    | `GRADLE_RO_DEP_CACHE=${HOME}/.gradle/caches ./gradlew tasks --all --gradle-user-home=/tmp/gradle-ro-home --no-daemon`                                                                          |

> [!IMPORTANT]
> **Verification Tier Policy**:
> - Choose the **lowest pyramid layer** that verifies the behavior safely (prefer Unit/Component slices over booting
    Testcontainers or full-stack E2E).
> - Use **Tier 1 (`./scripts/verify.sh` & `./scripts/sync-archify.sh`)** for rapid iterative feedback during development
>   loops; test execution is automatically scoped to affected modules (~3–10s).
> - **Tier 2 CI Gate (`.github/workflows/ci.yml`)** strictly enforces `./scripts/sync-archify.sh --check` and
>   `./scripts/verify.sh clean` on every PR.
> - **Tier 3 E2E** runs against `docker-compose.e2e.yml` + `e2e` profile via `../Android-ChatApp/scripts/e2e/` and is
>   never coupled to Tier 1/Tier 2 backend `./gradlew check`.
