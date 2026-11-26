# $0 Hybrid Free-Tier Production Deployment & Live Release Pipeline

| Field             | Value                                                                                                                        |
|:------------------|:-----------------------------------------------------------------------------------------------------------------------------|
| **Author**        | Nero Nguyen                                                                                                                  |
| **Status**        | Draft                                                                                                                        |
| **Date**          | 2026-11-26                                                                                                                   |
| **Linear Ticket** | [CHAT-26](https://linear.app/neronguyen/issue/NER-26/deploy-kmp-chatbackend-and-backing-services-on-dollar0-free-tier-stack) |
| **Target Repos**  | `KMP-ChatBackend` (primary owner), `Android-ChatApp` (release client pipeline)                                               |

--------------------------------------------------------------------------------

## Overview

This design specifies the `$0/month` production deployment architecture, CI/CD release pipeline, and signed Android
distribution path for running `KMP-ChatBackend` and `Android-ChatApp` live over the public internet (`https://` and
`wss://`) for Nero and an external tester.

The production runtime uses an already-provisioned **GCP Always Free `e2-micro`** VM (`us-west1-b`, Oregon, `x86_64`, 1
GB RAM + 2 GB swap, 30 GB `pd-standard` boot disk, `PREMIUM` network tier, Ubuntu 26.04 Minimal LTS) running three
strictly memory-capped containers: **Caddy** (DuckDNS + automatic Let's Encrypt HTTPS/WSS), **Spring Boot** (immutable
`linux/amd64` image pulled from GitHub Container Registry `ghcr.io`), and a **24 MB local persistence-disabled Redis**
container on the private Docker bridge for ephemeral OTP, rate-limit, and cache state. Durable data, async messaging,
and transactional email run on US-West free-tier managed services: **Neon Postgres** (`us-west-2`), **CloudAMQP Little
Lemur** (`us-west-2`), and **Brevo SMTP**. Backend verification and container builds run automatically in GitHub Actions
on push to `main`, while live deploys execute via manual `workflow_dispatch` over a forced-command SSH airlock with live
smoke verification and local rollback. `Android-ChatApp` ships a real-keystore-signed, R8-minified release APK via
GitHub Releases.

## The Problem

Although `KMP-ChatBackend` is containerized (`CHAT-25`) and both repositories pass local JVM and AVD E2E checks, four
gaps prevent a reliable `$0/month` live deployment on the provisioned GCP `e2-micro` VM:

1. **Compute, Memory, and Build Mismatch on a 1 GB VM**: The checked-in `docker-compose.prod.yml` builds the Gradle fat
   JAR (`-Xmx2048m`) directly on the production VM, requires a GitHub PAT (`GITHUB_TOKEN`) stored on the server,
   hardcodes a local `postgres` JDBC URL, and co-locates PostgreSQL 17, RabbitMQ 4, Redis, Spring Boot, and Caddy with
   zero container memory caps. On a 1 GB RAM `e2-micro` backed by a ~22-IOPS `pd-standard` disk, either building from
   source or running all five services causes Linux OOM kills and swap thrashing that freeze SSH and time out clients.
2. **Free-Tier Quota, Protocol, and Transit-Security Traps**:
    - `application.yaml` hardcodes HikariCP `minimum-idle: 5` and `tcpKeepAlive: true` without environment overrides.
      Holding five idle connections blocks Neon's 5-minute idle auto-suspend and burns ~182+ CU-hours/month against
      Neon's 100 CU-hours/month free ceiling, while omitting `prepareThreshold=0` causes prepared-statement collisions
      under Neon's PgBouncer (`-pooler`) transaction pooling.
    - `application.yaml` sets `default-requeue-rejected: true` with no Dead-Letter Queue. Any Brevo SMTP rejection
      (bounce, unverified sender, or daily quota hit) triggers an infinite requeue loop that exhausts CloudAMQP's 1M
      messages/month quota, Brevo's 300 emails/day limit, and GCP's 1 GB/month free egress allowance.
    - Offloading Redis to Redis Cloud Free (30 MB) omits TLS (`rediss://`), sending Redis passwords, OTP hashes, and
      cached chat messages in plaintext across public GCP-to-AWS hops while adding WAN round-trip latency to every
      IP-rate-limited call.
3. **Production Security, Health Probe, and WebSocket Jitter Gaps**:
    - `SecurityConfig.kt` leaves `/v3/api-docs/**` and `/swagger-ui/**` publicly permitted and `application-prod.yaml`
      does not disable SpringDoc. Once Swagger is disabled in production, zero unauthenticated `GET` endpoints return
      `200 OK` for deploy verification; conversely, running periodic Actuator checks against Postgres defeats Neon
      auto-suspend.
    - `ChatWebSocketHandler.kt` sets `PING_INTERVAL_MS = 30_000L` equal to `PONG_TIMEOUT_MS = 30_000L` and checks
      timeouts before sending the next ping. Any CPU scheduling jitter on `e2-micro` or WAN round-trip latency closes
      healthy WebSocket connections every 30 seconds (`SESSION_NOT_RELIABLE`).
4. **Untested Android Release Packaging**: `Android-ChatApp/app/build.gradle.kts` hardcodes `signingConfig = debug`,
   static `versionCode = 1`, and enables R8 minification (`isMinifyEnabled = true`) with zero custom
   `proguard-rules.pro` for pure-JVM `KMP-ChatContract` serialization classes, while `ChatWebSocketClient.kt` silently
   swallows JSON decode errors. `core/network/build.gradle.kts` falls back to `"http://localhost/"` without
   trailing-slash normalization, `AndroidManifest.xml` permits unencrypted Room backups (`allowBackup="true"`), and
   OkHttp's 15-second `callTimeout` can expire during a Neon cold wake-up paired with a token refresh retry.

### Proposed Solution

Adapt `KMP-ChatBackend` to a lean three-container `e2-micro` runtime (Caddy + GHCR-pulled Spring Boot + 24 MB local
ephemeral Redis) paired with Neon Postgres (`-pooler`, Hikari `minimum-idle: 0`), CloudAMQP Little Lemur, and Brevo
SMTP; separate CI test/image publishing from a manual forced-command SSH deploy with internal zero-I/O liveness probes,
deploy-only readiness verification, and automatic local rollback; include the 1-line `PONG_TIMEOUT_MS` WebSocket fix;
and wire a real-keystore-signed, R8-verified `Android-ChatApp` GitHub Release pipeline.

## Goals

* **G1 — Strict `$0/month` GCP Always Free Compliance**: Run the live backend on the existing `us-west1-b` `e2-micro` VM
  (`30 GB pd-standard`, `PREMIUM` network tier, 2 GB swapfile + `vm.swappiness=10`, `$1.00` billing budget alert) with
  Caddy blocking bare-IP and unknown-`Host` scanner traffic and all containers capped cleanly inside 1 GB physical RAM.
* **G2 — Quota-Safe & Private Backing-Service Wiring**: Keep Neon Postgres within 100 CU-hours/month via
  `HIKARI_MIN_IDLE=0` and PgBouncer-safe JDBC settings (`sslmode=require&prepareThreshold=0`); keep OTP, rate-limit, and
  cache state private on the local Docker bridge inside a 24 MB persistence-disabled Redis container; stop RabbitMQ
  infinite retry loops via `default-requeue-rejected: false` on CloudAMQP AMQPS (`5671`); and deliver live OTP emails
  via Brevo verified single-sender SMTP.
* **G3 — Hardened Production Surface & Stable WebSockets**: Disable SpringDoc/Swagger in `prod` (both in Spring config
  and Caddy), enforce Caddy `X-Real-IP` overwrite on a pinned Docker bridge subnet trusted by `TrustedProxyFilter`,
  expose internal-only Actuator probes on port `8081` (zero-I/O liveness for Docker `HEALTHCHECK`, deploy-only readiness
  for smoke verification), and fix `PONG_TIMEOUT_MS` (`30_000L` -> `70_000L`) so live `wss://` sessions stay connected
  under `e2-micro` jitter and WAN latency.
* **G4 — Gated GHCR Pipeline & Airlocked Manual Deploy with Rollback**: Gate `linux/amd64` GHCR image publishing on
  `./scripts/verify.sh clean` on push to `main` (eliminating Gradle builds and GitHub PATs on the VM), and execute live
  deploys via manual GitHub Actions `workflow_dispatch` through an OpenSSH forced-command wrapper
  (`/usr/local/bin/nerochat-deploy`) that verifies internal readiness and public HTTPS/Swagger-off invariants and rolls
  back automatically to the locally cached prior image tag on failure.
* **G5 — Signed, Upgradeable, R8-Verified Android GitHub Releases**: Replace debug release signing with GitHub Secrets
  keystore injection, add `app/proguard-rules.pro`, enforce HTTPS + trailing-slash `BASE_URL` guards, parameterize
  incrementing `versionCode`/`versionName`, disable unencrypted Room backups (`allowBackup="false"`), raise OkHttp
  `callTimeout` for Neon cold wake-ups, gate PR CI on `assembleRelease`, archive R8 `mapping.txt`, and verify the signed
  release APK end-to-end before sharing the GitHub Release link.
* **G6 — Aligned Governance & Documentation**: Track all branches, commits, PRs, and documentation under canonical
  ticket `CHAT-26`, keep this design document concise in `KMP-ChatBackend/docs/designs/`, rewrite
  `KMP-ChatBackend/docs/DEPLOYMENT.md` as the matching operational runbook, and update impacted module `README.md`
  contracts without adding vendor names to C4 `.archify.json` diagrams.

## Non-Goals

* **NG1 — Google Play Distribution**: No Play Console registration, Play App Signing, store listing, or 12-tester
  closed-testing tracks.
* **NG2 — Schema Migration Governance & Managed Backups**: Flyway SQL migrations (`NER-10`) and automated Postgres
  backup/PITR schedules are out of scope; Neon schemas remain disposable until `NER-10` lands.
* **NG3 — Horizontal Scaling & Full Observability Stack**: Multi-replica WebSocket fan-out (`NER-11`), Micrometer
  metrics/MDC distributed tracing (`NER-19`), and Transactional Outbox (`NER-16`) remain deferred.
* **NG4 — Remaining Wave 2 Functional Bugfixes**: `NER-5` (offline room-cache poisoning), `NER-6` (room-leave FK cascade
  and `VARCHAR(255)` limit), and `NER-7` (forced logout on transient refresh errors) stay in Wave 2; only the 1-line
  `PONG_TIMEOUT_MS` heartbeat fix and `allowBackup="false"` are pulled into `CHAT-26`.
* **NG5 — Zero-Downtime Blue-Green Cutovers**: Running two Spring Boot JVMs concurrently is impossible within 1 GB RAM;
  a ~15–25s single-container restart window during manual deploys is accepted.

## Open Questions

* **Live Brevo Single-Sender Gmail Deliverability**: Brevo single-sender mail sent from a `@gmail.com` address fails
  DMARC alignment and can land in Spam or bounce depending on the recipient provider. This must be validated empirically
  against the external tester's inbox during initial launch smoke testing; if Gmail hard-bounces single-sender mail, a
  custom domain or verified non-Gmail sender address will be required as an operational follow-up.

## Critical User Journeys (CUJs)

### CUJ 1: First-Time Live Launch & External Tester Onboarding

* **Given**: The GCP `e2-micro` VM (`nerochat-prod`) is running Caddy, Spring Boot (`prod` profile), and 24 MB local
  Redis; DuckDNS resolves `<name>.duckdns.org` to `136.118.143.119`; Neon schemas are bootstrapped; CloudAMQP and Brevo
  credentials are configured in `.env`; and a signed R8 `Android-ChatApp` APK built with
  `BASE_URL=https://<name>.duckdns.org/` is published on GitHub Releases.
* **When**: Nero and one external tester download and install the release APK, register two accounts, retrieve their
  6-digit OTPs from Brevo email delivery, verify accounts, log in, search for each other, and exchange real-time chat
  messages for > 2 minutes.
* **Then**: Caddy serves valid Let's Encrypt TLS for both `https://` and `wss://`, OTP emails arrive and verify cleanly,
  messages transition `SENDING` -> `SENT` and arrive live over WebSocket without 30-second ping-timeout disconnects, and
  `https://<name>.duckdns.org/v3/api-docs` returns `404`.

### CUJ 2: Controlled Production Release & Automated Rollback

* **Given**: A verified commit is merged to `KMP-ChatBackend/main`, GitHub Actions has passed
  `./scripts/verify.sh clean`, and `ghcr.io/<owner>/kmp-chatbackend:sha-<short>` is published while the previous
  known-good image tag is running on `nerochat-prod`.
* **When**: Nero triggers the manual `workflow_dispatch` deploy workflow in GitHub Actions targeting `sha-<short>`
  (either a healthy release or a drill with an intentionally misconfigured/broken image).
* **Then**: GitHub Actions invokes the forced-command SSH wrapper (`/usr/local/bin/nerochat-deploy sha-<short>`) without
  shell access or `.env` exposure. The VM pre-pulls the new image, saves the previous tag, swaps the backend container,
  verifies internal port `8081` `/actuator/health/liveness` + `/actuator/health/readiness` and public HTTPS `401`/`404`
  smoke assertions, and prunes stale images while keeping current + previous tags. If any check fails, the VM
  immediately restarts the locally cached previous tag, restores healthy service, and fails the CI job.

### CUJ 3: Idle Cost Conservation & Cold-Wake Recovery

* **Given**: Both users are inactive for more than 10 minutes while the VM stays online running Caddy, Spring Boot, and
  Docker's periodic `/actuator/health/liveness` check.
* **When**: Idle HikariCP connections drain to zero (`minimum-idle: 0`), Neon auto-suspends compute to 0 CU, and a user
  subsequently opens `Android-ChatApp` to log in or refresh their access token.
* **Then**: Docker liveness checks never wake Neon while idle; the incoming request wakes Neon transparently within the
  configured Hikari and OkHttp timeouts (no PgBouncer prepared-statement errors or premature 15-second client timeouts),
  and monthly Neon usage remains well under 100 CU-hours.

### CUJ 4: In-Place Android Release Upgrade

* **Given**: The external tester already has `v0.1.0` (`versionCode = 1`) of the signed R8 release APK installed with
  active login state and local Room chat history.
* **When**: A new tag (`v0.2.0`) is pushed to `Android-ChatApp`, GitHub Actions signs the R8 release APK with the same
  keystore and incremented `versionCode`, publishes it to GitHub Releases (archiving `mapping.txt`), and the tester
  installs the new APK over the existing app.
* **Then**: Android performs an in-place upgrade without `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, preserving local Room
  history and encrypted DataStore tokens, and connects cleanly over HTTPS/WSS.

--------------------------------------------------------------------------------

## Technical Plan (Architecture)

### 1. System Topology & Network Boundaries

Production runs three containers on the existing GCP Always Free `e2-micro` (`us-west1-b`, Oregon) inside a pinned
private Docker bridge subnet (`172.28.0.0/24`), talking over TLS to US-West managed services (`us-west-2`, Oregon) and
Brevo SMTP:

```text
Android-ChatApp (Signed R8 Release APK, GitHub Releases)
  │  HTTPS (:443) + WSS (/ws/chat)
  │  DNS: <name>.duckdns.org -> 136.118.143.119
  ▼
┌──────────────────────────────────────────────────────────────────────┐
│ GCP Always Free e2-micro (us-west1-b, 1 GB RAM + 2 GB Swap, 30 GB)   │
│ Inbound firewall: TCP 80, 443, 22 (key-only + forced-command CI key) │
│                                                                      │
│  ┌────────────────────────────────────────────────────────────────┐  │
│  │ Docker Bridge: nerochat_internal (pinned 172.28.0.0/24)        │  │
│  │                                                                │  │
│  │  Caddy (:80/:443, mem 96m)                                     │  │
│  │   - Auto Let's Encrypt TLS for {$DOMAIN}                       │  │
│  │   - Drops unknown Host / bare IP, blocks /v3/api-docs &        │  │
│  │     /swagger-ui & /actuator (404)                              │  │
│  │   - Overwrites header_up X-Real-IP {remote_host}               │  │
│  │         │ HTTP (:8080)                                         │  │
│  │         ▼                                                      │  │
│  │  Spring Boot Backend (ghcr.io/...:sha-<short>, mem 550m)       │  │
│  │   - App :8080 (bridge only) | Actuator :8081 (127.0.0.1 only)  │  │
│  │   - Profile: prod | PONG_TIMEOUT_MS = 70_000L                  │  │
│  │         │ RESP (:6379, bridge only)                            │  │
│  │         ▼                                                      │  │
│  │  Local Ephemeral Redis (redis:7-alpine, mem 48m, cap 24mb)     │  │
│  │   - No host port, save "", appendonly no, volatile-lru         │  │
│  └─────────┼──────────────────────────────────────────────────────┘  │
└────────────┼─────────────────┬────────────────────────┬──────────────┘
             │ JDBC + TLS      │ AMQPS (:5671)          │ SMTP (:587 STARTTLS)
             ▼                 ▼                        ▼
      Neon Postgres     CloudAMQP Little Lemur      Brevo SMTP
      (us-west-2,       (us-west-2, 1M/mo,          (300 emails/day,
       -pooler,          vhost=<username>,           verified sender)
       min-idle=0)       requeue=false)

GitHub Actions (public repos, $0)
  - KMP-ChatBackend push -> verify.sh clean -> build & push linux/amd64 to GHCR
  - KMP-ChatBackend workflow_dispatch -> SSH forced-command (/usr/local/bin/nerochat-deploy)
  - Android-ChatApp tag/dispatch -> verify.sh clean -> signed R8 APK -> GitHub Releases
```

### 2. Compute, Memory & Disk Budget (`e2-micro` 1 GB RAM + 30 GB `pd-standard`)

To stay strictly inside 1 GB physical RAM without hitting the ~22-IOPS `pd-standard` swap cliff, every container, thread
pool, and log stream is bounded explicitly:

| Layer / Component                                 | Memory Limit      | Runtime Caps & Hygiene                                                                                                                                                                            |
|:--------------------------------------------------|:------------------|:--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Ubuntu 26.04 Minimal + `dockerd` / `containerd`   | ~220–260 MB       | 2 GB swapfile with `vm.swappiness=10` as emergency cushion only                                                                                                                                   |
| `caddy` (`caddy:2-alpine`)                        | `mem_limit: 96m`  | Named volumes `caddy_data` & `caddy_config`; `json-file` logs `10m x 3`                                                                                                                           |
| `redis` (`redis:7-alpine`)                        | `mem_limit: 48m`  | `--maxmemory 24mb --maxmemory-policy volatile-lru --save "" --appendonly no --requirepass ${REDIS_PASSWORD}`                                                                                      |
| `backend` (`eclipse-temurin:21-jre-alpine`)       | `mem_limit: 550m` | `JAVA_TOOL_OPTIONS="-XX:+UseSerialGC -Xms128m -Xmx288m -XX:MaxMetaspaceSize=160m -XX:MaxDirectMemorySize=64m -XX:ReservedCodeCacheSize=48m -XX:+ExitOnOutOfMemoryError"`                          |
| Thread & Listener Pools (`application-prod.yaml`) | Inside `550m`     | Tomcat `threads.max: 16`, `min-spare: 4`; Redisson `threads: 2`, `nettyThreads: 4`, `connectionPoolSize: 4`, `subscriptionConnectionPoolSize: 2`; RabbitMQ `concurrency: 1`, `max-concurrency: 1` |
| Disk Hygiene (30 GB `pd-standard`)                | < 10 GB steady    | Docker `json-file` `max-size: 10m`, `max-file: 3` on all containers; deploy script prunes stale images after healthy swap while retaining current + previous tag                                  |

### 3. Managed Backing Services & Quota Invariants

* **Neon Postgres (`us-west-2`, 100 CU-hours/month, 5-min auto-suspend)**:
    * Parameterize HikariCP in `application.yaml` with `application-prod.yaml` defaults tuned for serverless
      scale-to-zero: `minimum-idle: ${HIKARI_MIN_IDLE:0}`, `maximum-pool-size: ${HIKARI_MAX_POOL_SIZE:4}`,
      `idle-timeout: ${HIKARI_IDLE_TIMEOUT:60000}`, `max-lifetime: ${HIKARI_MAX_LIFETIME:300000}`,
      `connection-timeout: ${HIKARI_CONNECTION_TIMEOUT:30000}`, and `keepalive-time: 0`.
    * Runtime `POSTGRES_URL` targets Neon's `-pooler` endpoint with
      `?sslmode=require&prepareThreshold=0&preparedStatementCacheQueries=0` to prevent PgBouncer transaction-mode
      prepared-statement collisions.
    * One-time initial schema creation (`user_service` and `chat_service` schemas before Flyway `NER-10`) runs against
      Neon's **direct** (non-pooler) URL with `JPA_DDL_AUTO=update` and `JPA_CREATE_NAMESPACES=true`, then switches
      permanently to `-pooler` with `JPA_DDL_AUTO=validate` and `JPA_CREATE_NAMESPACES=false`.
* **Local Ephemeral Redis (`redis:7-alpine`, private bridge)**:
    * Serves `RedissonOtpService` (180s TTL), `IpRateLimitingService` / `EmailRateLimitingService`, and
      `CacheNames.MESSAGES` (30m TTL) over the private `172.28.0.0/24` bridge with zero WAN exposure and no host port
      binding.
    * Bound `CacheNames.MESSAGES` in `RedisConfig.kt` with `maxSize = 200` so cached message slices cannot crowd out
      active OTP keys inside the 24 MB ceiling.
* **CloudAMQP Little Lemur (`us-west-2`, 1M msgs/mo, 20 conns)**:
    * Uses AMQPS (`RABBITMQ_PORT=5671`, `RABBITMQ_SSL_ENABLED=true`) and `RABBITMQ_VHOST=<cloudamqp-8-char-username>`.
    * Set `spring.rabbitmq.listener.simple.default-requeue-rejected: false` in `application.yaml` so permanently failing
      SMTP jobs stop after the 3 exponential retries instead of looping infinitely.
    * Gate `ChatEvent.NewMessage` publishing in `ChatMessageService.kt` behind a configurable property
      (`chat.events.publish-enabled`, default `false` in `prod`) because zero queues currently bind to `chat.events`.
* **Brevo SMTP (`smtp-relay.brevo.com`, 300 emails/day)**:
    * Uses port `587` with `STARTTLS` (`SMTP_USERNAME` = Brevo SMTP login, `SMTP_SENDER` = verified single-sender
      address).

### 4. Edge Security, Split Actuator Probes & WebSocket Stability

* **Caddy Edge (`Caddyfile`)**:
    * Serves only `{$DOMAIN}` with `reverse_proxy backend:8080 { header_up X-Real-IP {remote_host} }`, structured stdout
      access logs (`log { output stdout }`), and explicit `respond 404` blocks for `/v3/api-docs*`, `/swagger-ui*`, and
      `/actuator*`.
    * Rejects bare-IP or unknown-`Host` traffic on `:80`/`:443` with minimal connection abort to minimize scanner egress
      against GCP's China/Australia-excluded 1 GB allowance.
* **Spring Security & Proxy Trust**:
    * Pin `nerochat_internal` subnet to `172.28.0.0/24` (inside `TrustedProxyFilter`'s `172.16.0.0/12` CIDR).
    * Disable SpringDoc in `application-prod.yaml` (`springdoc.api-docs.enabled: false`,
      `springdoc.swagger-ui.enabled: false`), annotate `OpenApiConfig` with `@Profile("!prod")`, and restrict Swagger
      `permitAll()` routes in `SecurityConfig.kt` when SpringDoc is disabled (preserving `OpenApiExportTest` on
      default/non-prod profiles).
* **Split Actuator Probes on Internal Port `8081` (Zero-I/O Liveness vs. Deploy-Only Readiness)**:
    * Add `spring-boot-starter-actuator` (`management.server.port: 8081`, `management.server.address: 127.0.0.1`,
      `management.endpoint.health.probes.enabled: true`, `management.endpoints.web.exposure.include: health`). Port
      `8081` is never published on the VM host and never proxied by Caddy.
    * Container `HEALTHCHECK` runs BusyBox `wget -q --spider http://127.0.0.1:8081/actuator/health/liveness` every 30s —
      touching only in-memory JVM state so Neon remains suspended during idle windows.
    * Deploy smoke verification invokes `http://127.0.0.1:8081/actuator/health/readiness` **once** inside the backend
      container via `docker exec` to verify Postgres, Redis, and RabbitMQ connectivity at release time.
* **WebSocket Heartbeat Fix**:
    * Update `PONG_TIMEOUT_MS` in `ChatWebSocketHandler.kt` from `30_000L` to `70_000L` (> 2x
      `PING_INTERVAL_MS = 30_000L`), absorbing `e2-micro` CPU jitter and US-West round-trip latency without closing
      healthy sockets.

### 5. CI/CD Pipeline, Forced-Command SSH Airlock & Local Rollback

1. **Automated Test & Immutable Image Publish (`KMP-ChatBackend/.github/workflows/ci.yml`)**:
    * On every PR and push to `main`: runs `./scripts/sync-archify.sh --check` and `./scripts/verify.sh clean`.
    * On push to `main` (separate `publish` job with `needs: test`, `packages: write`): builds `linux/amd64` Docker
      image via BuildKit secrets (`GITHUB_ACTOR`, `GITHUB_TOKEN`) and pushes immutable tag
      `ghcr.io/<owner>/kmp-chatbackend:sha-<short>` (plus `:main`). No Gradle toolchain or GitHub PAT is stored on the
      VM.
2. **Airlocked Manual Deploy (`KMP-ChatBackend/.github/workflows/deploy.yml`)**:
    * Triggered strictly by `workflow_dispatch` with input `image_tag` (regex-validated `^sha-[0-9a-f]{7,40}$`) and
      `concurrency.cancel-in-progress: false` so active cutovers are never killed mid-swap.
    * Connects to `nerochat-prod` using a dedicated CI SSH key locked in `~/.ssh/authorized_keys` to:
      `command="/usr/local/bin/nerochat-deploy",no-port-forwarding,no-X11-forwarding,no-agent-forwarding,no-pty`
    * The CI key cannot open an interactive shell or read `/opt/nerochat/.env`.
3. **Atomic VM Cutover & Self-Healing Rollback (`/usr/local/bin/nerochat-deploy`)**:
    * Validates `$SSH_ORIGINAL_COMMAND` strictly against `^sha-[0-9a-f]{7,40}$`.
    * Pre-pulls `ghcr.io/<owner>/kmp-chatbackend:<tag>` while the existing backend continues serving.
    * Records currently running tag into `/opt/nerochat/.deploy-state/prev_tag` (handling first-boot absence cleanly).
    * Executes single-container stop-swap
      (`IMAGE_TAG=<tag> docker compose -f docker-compose.prod.yml up -d --no-deps backend`).
    * Runs the 4-step deterministic smoke gate:
        1. Poll `docker exec nerochat-backend wget -qO- http://127.0.0.1:8081/actuator/health/liveness` -> `UP` (90s
           budget for 0.25 vCPU cold JVM boot).
        2. One-shot `docker exec nerochat-backend wget -qO- http://127.0.0.1:8081/actuator/health/readiness` -> `UP`
           (confirms Neon + Redis + CloudAMQP).
        3. External HTTPS check: `GET https://$DOMAIN/api/chat/rooms` -> HTTP `401` with body containing
           `CommonServerError.Unauthorized` (proves DNS + Let's Encrypt TLS + Caddy `X-Real-IP` + `TrustedProxyFilter` +
           `SecurityConfig` + `ContractJson`).
        4. External HTTPS check: `GET https://$DOMAIN/v3/api-docs` and `/swagger-ui/index.html` -> HTTP `404`; assert
           container `RestartCount == 0`.
    * On success: writes `<tag>` to `current_tag` and prunes older unused backend images while preserving `current_tag`
      and `prev_tag`.
    * On any failure: restarts `prev_tag` from local disk cache (zero network dependency), re-verifies `/liveness`, and
      exits non-zero to fail the GitHub Actions job.

### 6. Android Signed R8 Release Architecture (`Android-ChatApp`)

* **Keystore Custody & Signing (`app/build.gradle.kts`)**:
    * Replace hardcoded `signingConfigs.getByName("debug")` on `buildTypes.release` with a real `release` signing config
      reading `RELEASE_KEYSTORE_PATH`, `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS`, and `RELEASE_KEY_PASSWORD` from
      Gradle properties / environment variables.
    * Fail fast if release signing properties are missing when a release assemble/bundle task is invoked (never silently
      falling back to debug signing). Store the `.jks` backup offline in a password manager and base64-encoded in GitHub
      Secrets.
* **Monotonic Versioning**:
    * Parameterize `versionCode` (from `-PVERSION_CODE`, e.g., `GITHUB_RUN_NUMBER` or monotonic integer) and
      `versionName` (from `-PVERSION_NAME`, e.g., git tag `0.1.0`), defaulting locally to `1` / `"0.1.0-local"`,
      ensuring every GitHub Release upgrades in-place cleanly.
* **Network & Storage Hardening (`core/network/build.gradle.kts`, `NetworkModule.kt`, `AndroidManifest.xml`)**:
    * Normalize `BASE_URL` to always end with `/` (preventing Retrofit `IllegalArgumentException`) and fail release
      Gradle tasks if `BASE_URL` is missing, points to `localhost`, or does not start with `https://`.
    * Increase `@BaseOkHttp` `callTimeout` in `NetworkModule.kt` from `15.seconds` to `30.seconds` so Neon cold
      wake-up + nested `TokenAuthenticator` refresh retries complete cleanly.
    * Set `android:allowBackup="false"` in `AndroidManifest.xml` so unencrypted Room SQLite chat tables are never
      uploaded to Google Auto Backup.
* **R8 Full-Mode Keep Rules (`app/proguard-rules.pro`) & Release CI (`.github/workflows/release.yml`)**:
    * Add `app/proguard-rules.pro` keeping `io.github.neronguyenvn.nerochat.contract.**` (`@Serializable` companions,
      sealed hierarchies `WsClientMessage`, `WsServerEvent`, `ServerError`, `@SerialName` discriminators), private
      Retrofit service interfaces (`RetrofitAuthNetworkApi`, `RetrofitChatNetworkApi`), Navigation 3 `@Serializable`
      route keys, `core/datastore` `UserData`, and Hilt `WorkManager` workers.
    * Add a CI R8 compilation check (`assembleRelease` with `BASE_URL=https://example.invalid/` and ephemeral test
      signing or unsigned check) to `.github/workflows/ci.yml` so R8 breaks are caught on every PR.
    * Add `.github/workflows/release.yml` (triggered by `v*` tag push or `workflow_dispatch`) that runs
      `./scripts/verify.sh clean`, decodes the release keystore secret, builds signed `assembleRelease` against
      `vars.PROD_BASE_URL`, generates SHA-256 checksums, archives R8 `mapping.txt` as a workflow artifact, and creates a
      draft GitHub Release with tester install + Brevo Spam-check notes (published after live device smoke testing).

--------------------------------------------------------------------------------

## Alternatives Considered

### Alternative A: OCI Always Free ARM (4 OCPU / 24 GB RAM, All-in-One Docker Compose)

* **Pros**: Generous 24 GB RAM allows co-locating PostgreSQL 17, Redis 7, RabbitMQ 4, Spring Boot, and Caddy on a single
  host with zero external database or message-broker accounts.
* **Cons**: Singapore (`ap-singapore-1`) Always Free Ampere A1 capacity is frequently exhausted unless upgrading to
  Pay-As-You-Go or scripting retries.
* **Reason for Rejection**: *Rejected by author: A GCP Always Free `e2-micro` VM (`us-west1-b`, `30 GB pd-standard`) is
  already provisioned; adapting the topology to GCP + US-West managed services avoids OCI capacity hunting.*

### Alternative B: Co-Locating PostgreSQL 17, RabbitMQ 4, Redis, Spring Boot & Caddy on GCP `e2-micro`

* **Pros**: Reuses the existing `docker-compose.prod.yml` all-in-one layout without external Neon or CloudAMQP accounts.
* **Cons**: Spring Boot JVM + PostgreSQL 17 + RabbitMQ 4 (Erlang VM) + Redis + Caddy exceed 1 GB physical RAM
  immediately and thrash the ~22-IOPS `pd-standard` swap disk, stalling GC and locking out SSH.
* **Reason for Rejection**: *Rejected during analysis: Physically infeasible within 1 GB RAM without OOM kills or severe
  swap thrashing.*

### Alternative C: Managed Redis Cloud Free (30 MB / 30 Connections, Plain TCP)

* **Pros**: Keeps the VM stateless across reboots and saves ~35–48 MB of local VM RAM.
* **Cons**: Redis Cloud's free 30 MB tier omits TLS (`rediss://`), exposing the Redis `AUTH` password, OTP hashes, and
  cached chat messages (`CacheNames.MESSAGES`) in plaintext across public GCP-to-AWS internet hops while adding WAN
  latency, GCP egress, and a 30-connection / 100 ops/s cap to every IP-rate-limited call.
* **Reason for Rejection**: *Rejected by author: Moving ephemeral 3-minute OTPs, IP rate limits, and a 200-entry message
  cache to a 24 MB persistence-disabled local Redis container eliminates plaintext WAN leaks, removes one external
  vendor, and keeps rate-limit latency sub-millisecond with zero code rewrite.*

### Alternative D: Auto-Deploying on Every Push to `main` (Without Manual Trigger)

* **Pros**: Zero manual clicks between merging a PR and updating the live VM.
* **Cons**: Routine documentation or WIP merges restart the live backend mid-conversation for connected testers; before
  Flyway (`NER-10`) lands, container rollback cannot undo Hibernate `ddl-auto=update` schema changes.
* **Reason for Rejection**: *Rejected by author: Automated CI verification and immutable GHCR image publishing run on
  every push to `main`, while live cutovers wait for an explicit manual `workflow_dispatch` trigger until Flyway (
  `NER-10`) makes schema evolution versioned.*

### Alternative E: Local Debug APK (`-PBASE_URL=...`) or Google Play Internal Testing

* **Pros**: A local debug APK requires zero keystore setup or ProGuard rules today; Google Play Internal Testing
  provides automated Play Store delivery for up to 100 testers.
* **Cons**: Switching later from a debug-signed APK to a release-signed APK triggers
  `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, forcing the tester to uninstall and wipe local Room chat history; Google Play
  Console costs a `$25` registration fee and store metadata overhead just for one friend.
* **Reason for Rejection**: *Rejected by author: Shipping a real-keystore-signed R8 release APK via GitHub Releases
  costs `$0`, preserves in-place upgrades from day one, and verifies R8 keep rules early.*

### Alternative F: Supabase Free (PostgreSQL) & Resend SMTP (Email)

* **Pros**: Supabase provides a full Postgres dashboard; Resend offers developer-friendly SMTP/API tooling.
* **Cons**: Supabase Free pauses the entire database project after 7 days of inactivity, requiring manual dashboard
  resume; Resend requires owned-domain DNS `SPF`/`DKIM` verification to email external recipients and cannot work over
  DuckDNS.
* **Reason for Rejection**: *Rejected during analysis: Neon auto-wakes automatically on incoming TCP connections when
  Hikari `minimum-idle: 0`, and Brevo allows single-sender SMTP without purchasing a custom domain.*

### Alternative G: On-VM Docker Builds, Local Laptop Tunneling (`cloudflared`), or Wildcard DNS (`sslip.io`)

* **Pros**: On-VM builds skip GHCR setup; `cloudflared` needs no cloud VM; `136-118-143-119.sslip.io` needs no DNS
  account signup.
* **Cons**: Gradle (`-Xmx2048m`) OOMs a 1 GB VM and requires storing a GitHub PAT on disk; local tunneling stops working
  whenever the developer's laptop sleeps; `sslip.io` embeds the raw IP in the URL and breaks if the VM IP ever changes.
* **Reason for Rejection**: *Rejected by author: Pre-built GHCR images eliminate VM build OOMs and host PATs, GCP
  `e2-micro` provides 24/7 uptime, and DuckDNS provides a readable subdomain (`<name>.duckdns.org`) independent of IP
  changes.*

--------------------------------------------------------------------------------

## Non-Functional Requirements

### Security & Privacy

* **Zero Host Exposure of Internal Services**: Only TCP `80`, `443`, and key-only `22` are open on `nerochat-prod`.
  Backend (`8080`), Actuator (`8081`, bound to `127.0.0.1` inside container), and local Redis (`6379`) never bind public
  host ports.
* **Least-Privilege CI Airlock**: The CI SSH key is restricted in `~/.ssh/authorized_keys` to
  `/usr/local/bin/nerochat-deploy` with port/agent/X11/PTY forwarding disabled, preventing shell access or `.env`
  exfiltration. No GitHub PAT is stored on the VM.
* **Transport & Client Privacy**: External traffic uses Caddy Let's Encrypt TLS (`https://`/`wss://`); Neon uses
  `sslmode=require`; CloudAMQP uses AMQPS (`5671`); Brevo uses STARTTLS (`587`); local Redis traffic stays inside
  `172.28.0.0/24`; public Swagger is disabled in Spring and blocked at Caddy; Android release builds enforce `https://`
  `BASE_URL`, `HttpLoggingInterceptor.Level.NONE`, and `android:allowBackup="false"`.
* **Keystore Custody**: The Android release `.jks` keystore is never committed to Git; encrypted copies live both in
  GitHub Secrets and an offline password manager.

### Cost & Quota Economics (`$0.00/month` Target)

* **GCP Always Free**: `e2-micro`, `us-west1-b`, `30 GB pd-standard`, `PREMIUM` network tier (required for the 1
  GB/month North America egress credit), external IP attached only while VM runs, `$1.00` billing budget alert enabled.
* **Neon Free**: `<= 100 CU-hours/month`, `0.5 GB` storage; enforced by `HIKARI_MIN_IDLE=0` and zero-I/O Docker
  `/liveness` probes.
* **CloudAMQP & Brevo Free**: `<= 1M msgs/month` / `20 conns` (protected by `default-requeue-rejected: false` and gating
  unused `chat.events` publishes) and `<= 300 emails/day`.

### Performance & Latency

* **1 GB RAM Envelope**: Combined container memory limits (`550m` backend + `96m` Caddy + `48m` Redis = `694m`) leave ~
  300 MB for Ubuntu 26.04 Minimal and `dockerd`, keeping steady-state JVM heap off the 22-IOPS swapfile.
* **Cold-Wake Tolerance**: `HIKARI_CONNECTION_TIMEOUT=30000` (backend) and `callTimeout(30.seconds)` (Android OkHttp)
  absorb Neon's 1–3s serverless wake-up plus `e2-micro` CPU burst latency without false timeouts.

### Rollout & Rollback

* **Immutable Tag Promotion**: Every backend release is pinned by `sha-<short>` tag; `deploy.yml` enforces
  `cancel-in-progress: false`.
* **Autonomous Local Rollback**: `/usr/local/bin/nerochat-deploy` preserves `prev_tag` on local disk and automatically
  restarts it if any of the 4 internal/external smoke checks fail (valid for backward-compatible app releases; routine
  deploys run `JPA_DDL_AUTO=validate`).

--------------------------------------------------------------------------------

## Risks & Mitigations

* **Brevo `@gmail.com` Single-Sender DMARC Rejection**: Sending OTP emails from a `@gmail.com` address through Brevo
  fails DMARC alignment and can land in Spam or hard-bounce.
    * *Mitigation*: Validate live delivery to both Nero's and the external tester's real inboxes during launch smoke
      testing; inspect Brevo transactional logs on bounce; include a "check Spam" note in GitHub Release notes; switch
      to a verified custom-domain sender if hard bounces occur.
* **GCP China/Australia Scanner Egress**: Unsolicited bots from regions excluded from GCP's 1 GB free tier can hit
  `136.118.143.119` and incur small egress fees.
    * *Mitigation*: Configure Caddy to answer only `{$DOMAIN}` and close unknown-`Host` / bare-IP connections
      immediately, and configure a `$1.00` GCP billing alert.
* **R8 Full-Mode Stripping Hidden by Silent WebSocket Catch**: `ChatWebSocketClient.kt` silently ignores JSON decode
  exceptions, so R8 stripping of `KMP-ChatContract` polymorphic serializers could break live chat without crashing.
    * *Mitigation*: Add explicit `app/proguard-rules.pro` keep rules for `io.github.neronguyenvn.nerochat.contract.**`
      and Retrofit/Nav3/Hilt types, gate PR CI on `assembleRelease`, archive `mapping.txt`, and run a mandatory live
      device smoke test on the signed R8 APK before publishing any draft GitHub Release.
* **Non-Reversible Schema Changes Before Flyway (`NER-10`)**: Automated container rollback cannot undo database
  mutations made if `JPA_DDL_AUTO=update` is left enabled.
    * *Mitigation*: Restrict `JPA_DDL_AUTO=update` strictly to the one-time initial bootstrap on Neon's direct endpoint,
      then lock `.env` to `JPA_DDL_AUTO=validate` for all routine deploys; treat Neon schemas as disposable until
      `NER-10` lands.
* **Android Keystore Loss Locking Out Upgrades**: Losing the `.jks` file or passwords prevents signing compatible future
  APKs.
    * *Mitigation*: Store the keystore and credentials in both GitHub Secrets and an offline password manager before
      shipping `v0.1.0`.
* **DuckDNS Intermittent Outages or Ephemeral IP Drift**: DuckDNS occasionally has brief DNS delays, and an ephemeral VM
  IP changes on VM stop/start.
    * *Mitigation*: Persist Caddy's `caddy_data` volume (never `docker compose down -v`) to avoid Let's Encrypt rate
      limits, and either promote `136.118.143.119` to an attached static IP (while VM runs) or install a lightweight
      DuckDNS systemd timer on the host.

--------------------------------------------------------------------------------

## Detailed Implementation Plan

All changes execute across two repositories under branch `infra/chat-26-gcp-managed-deployment` (canonical ticket
`CHAT-26`) without changing any `KMP-ChatContract` wire schemas.

### Target Files to Create/Modify

#### Part A — `KMP-ChatBackend`

##### 1. `KMP-ChatBackend/gradle/libs.versions.toml` (Modify)

* **Type / Scope**: Gradle Version Catalog
* **Proposed Changes**: Add `spring-boot-starter-actuator` library alias
  (`org.springframework.boot:spring-boot-starter-actuator`) under `[libraries]` so `:app` can expose internal split
  liveness/readiness probes.

##### 2. `KMP-ChatBackend/app/build.gradle.kts` (Modify)

* **Type / Scope**: Gradle Module Build
* **Proposed Changes**: Add `implementation(libs.spring.boot.starter.actuator)` to `:app` dependencies.

##### 3. `KMP-ChatBackend/app/src/main/resources/application.yaml` (Modify)

* **Type / Scope**: Shared Spring Boot Configuration
* **Proposed Changes**:
    * Parameterize HikariCP properties so production can drain idle connections for Neon scale-to-zero without altering
      local defaults: `maximum-pool-size: ${HIKARI_MAX_POOL_SIZE:10}`, `minimum-idle: ${HIKARI_MIN_IDLE:5}`,
      `connection-timeout: ${HIKARI_CONNECTION_TIMEOUT:20000}`, `idle-timeout: ${HIKARI_IDLE_TIMEOUT:300000}`,
      `max-lifetime: ${HIKARI_MAX_LIFETIME:1800000}`, `keepalive-time: ${HIKARI_KEEPALIVE_TIME:0}`.
    * Parameterize Redisson thread counts (`threads: ${REDIS_THREADS:2}`, `nettyThreads: ${REDIS_NETTY_THREADS:4}`) and
      add `pingConnectionInterval: 60000` inside `singleServerConfig:` to cap Netty memory on 1 GB RAM.
    * Flip `spring.rabbitmq.listener.simple.default-requeue-rejected` from `true` to `false` (preventing infinite SMTP
      retry loops on CloudAMQP/Brevo failures) and parameterize `max-concurrency: ${RABBITMQ_MAX_CONCURRENCY:3}`.
    * Add `chat.events.publish-enabled: ${CHAT_EVENTS_PUBLISH_ENABLED:true}` default flag so non-prod/tests keep
      existing behavior while `prod` skips unused `chat.events` publishes.

##### 4. `KMP-ChatBackend/app/src/main/resources/application-prod.yaml` (Modify)

* **Type / Scope**: Production Spring Profile Configuration
* **Proposed Changes**:
    * Set Neon-safe Hikari defaults: `HIKARI_MIN_IDLE=0`, `HIKARI_MAX_POOL_SIZE=4`, `HIKARI_IDLE_TIMEOUT=60000`,
      `HIKARI_MAX_LIFETIME=300000`, `HIKARI_CONNECTION_TIMEOUT=30000`.
    * Cap Tomcat threads (`server.tomcat.threads.max: 16`, `min-spare: 4`) and RabbitMQ listener (`max-concurrency: 1`)
      for the 1 GB `e2-micro` budget.
    * Disable public OpenAPI/Swagger: `springdoc.api-docs.enabled: false`, `springdoc.swagger-ui.enabled: false`.
    * Configure internal-only Actuator probes: `management.server.port: 8081`, `management.server.address: 127.0.0.1`,
      `management.endpoints.web.exposure.include: health`, `management.endpoint.health.probes.enabled: true`,
      `management.endpoint.health.show-details: never`.
    * Set `chat.events.publish-enabled: false` so unconsumed `ChatEvent.NewMessage` frames do not burn CloudAMQP quota.

##### 5. `KMP-ChatBackend/app/src/main/kotlin/io/github/neronguyenvn/nerochat/api/config/OpenApiConfig.kt` (Modify)

* **Type / Scope**: Spring Configuration
* **Proposed Changes**: Annotate `OpenApiConfig` with `@Profile("!prod")` so SpringDoc schema beans are not instantiated
  on the 1 GB production VM, while keeping `OpenApiExportTest` intact on default/dev profiles.

##### 6. `KMP-ChatBackend/app/src/main/kotlin/io/github/neronguyenvn/nerochat/api/security/SecurityConfig.kt` (Modify)

* **Type / Scope**: Spring Security Filter Chain
* **Proposed Changes**: Inject `@Value("\${springdoc.api-docs.enabled:true}")` (or `Environment`) and only register
  `.requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()` when SpringDoc is enabled,
  removing public Swagger permits in `prod`.

##### 7. `KMP-ChatBackend/app/src/main/kotlin/io/github/neronguyenvn/nerochat/infra/caching/RedisConfig.kt` (Modify)

* **Type / Scope**: Cache Configuration
* **Proposed Changes**: Set `maxSize = 200` on `CacheNames.MESSAGES`'s `CacheConfig` so message slice caching stays
  bounded inside the 24 MB local Redis container and cannot evict active OTP keys.

##### 8. `KMP-ChatBackend/chat/src/main/kotlin/io/github/neronguyenvn/nerochat/chat/api/websocket/ChatWebSocketHandler.kt` (Modify)

* **Type / Scope**: WebSocket Handler (`:chat`)
* **Proposed Changes**: Increase `PONG_TIMEOUT_MS` from `30_000L` to `70_000L` (more than 2x
  `PING_INTERVAL_MS = 30_000L`) so `e2-micro` CPU scheduling jitter and US-West round-trip latency do not disconnect
  healthy clients every 30 seconds.

##### 9. `KMP-ChatBackend/chat/src/main/kotlin/io/github/neronguyenvn/nerochat/chat/service/ChatMessageService.kt` (Modify)

* **Type / Scope**: Chat Domain Service (`:chat`)
* **Proposed Changes**: Inject
  `@Value("\${chat.events.publish-enabled:true}") private val publishChatEvents: Boolean = true` and guard the
  `eventPublisher.publish(ChatEvent.NewMessage(...))` call so `prod` skips unconsumed RabbitMQ publishes while existing
  unit tests keep default `true`.

##### 10. `KMP-ChatBackend/Caddyfile` (Modify)

* **Type / Scope**: Edge Reverse Proxy Config
* **Proposed Changes**:
    * Enable structured stdout access logging (`log { output stdout }`).
    * Keep `{$DOMAIN}` block with `reverse_proxy backend:8080 { header_up X-Real-IP {remote_host} }` and explicit
      `respond 404` matchers for `/v3/api-docs*`, `/swagger-ui*`, and `/actuator*`.
    * Add a catch-all `:80` / unknown-host abort rule so bare-IP scanner traffic against `136.118.143.119` does not burn
      GCP's China/Australia-excluded egress.

##### 11. `KMP-ChatBackend/docker-compose.prod.yml` (Modify)

* **Type / Scope**: Production Container Compose Topology
* **Proposed Changes**:
    * Replace local `build:` with pre-built GHCR image
      `ghcr.io/${GHCR_OWNER:-neronguyenvn}/kmp-chatbackend:${IMAGE_TAG:-main}`.
    * Keep only `caddy` (`mem_limit: 96m`), `backend` (`mem_limit: 550m`, explicit `JAVA_TOOL_OPTIONS` with
      `-XX:+UseSerialGC -Xms128m -Xmx288m -XX:MaxMetaspaceSize=160m -XX:MaxDirectMemorySize=64m -XX:ReservedCodeCacheSize=48m -XX:+ExitOnOutOfMemoryError`,
      Docker `HEALTHCHECK` polling `http://127.0.0.1:8081/actuator/health/liveness` via BusyBox `wget`), and local
      ephemeral `redis` (`redis:7-alpine`, `mem_limit: 48m`,
      `--maxmemory 24mb --maxmemory-policy volatile-lru --save "" --appendonly no --requirepass ${REDIS_PASSWORD}`, no
      host port).
    * Pass `POSTGRES_URL` through from `.env` (removing hardcoded local `postgres` override) and remove mandatory local
      `postgres`/`rabbitmq` services from the default runtime.
    * Pin `nerochat_internal` subnet explicitly to `172.28.0.0/24` (inside `TrustedProxyFilter`'s `172.16.0.0/12`) and
      attach `json-file` logging limits (`max-size: "10m"`, `max-file: "3"`) to all three containers.

##### 12. `KMP-ChatBackend/.env.example` (Modify)

* **Type / Scope**: Environment Contract Template
* **Proposed Changes**: Document every required production variable for the GCP + Neon + Local Redis + CloudAMQP +
  Brevo + DuckDNS stack (including Neon `-pooler` +
  `?sslmode=require&prepareThreshold=0&preparedStatementCacheQueries=0`, Neon direct URL bootstrap note, CloudAMQP
  8-char `RABBITMQ_VHOST` + port `5671`, Brevo SMTP login vs verified `SMTP_SENDER`, and removal of host `GITHUB_TOKEN`
  PAT).

##### 13. `KMP-ChatBackend/scripts/deploy/nerochat-deploy.sh` (Create)

* **Type / Scope**: Version-Controlled VM Airlock Deploy Script (installed to `/usr/local/bin/nerochat-deploy`)
* **Proposed Changes**: Implement the atomic single-container cutover script invoked via OpenSSH forced command:
  validate `$SSH_ORIGINAL_COMMAND` against `^sha-[0-9a-f]{7,40}$`, pre-pull image from GHCR, save previous tag to
  `.deploy-state/prev_tag`, swap `backend`, poll internal port `8081` `/liveness` (90s timeout), execute one-shot
  `/readiness` (`200`), execute external HTTPS checks (`GET /api/chat/rooms` -> `401` +
  `CommonServerError.Unauthorized`; `GET /v3/api-docs` -> `404`; `RestartCount == 0`), prune old images keeping
  current + previous on success, and automatically restart `prev_tag` from local disk on any failure.

##### 14. `KMP-ChatBackend/.github/workflows/ci.yml` (Modify)

* **Type / Scope**: Backend CI & GHCR Image Publish Workflow
* **Proposed Changes**: Keep existing `test` job (`sync-archify.sh --check` + `./scripts/verify.sh clean`); add a
  `publish` job (`if: github.event_name == 'push' && github.ref == 'refs/heads/main'`, `needs: test`,
  `permissions: { contents: read, packages: write }`) that builds `linux/amd64` with BuildKit secrets and pushes
  `ghcr.io/<owner>/kmp-chatbackend:sha-<short>` and `:main`.

##### 15. `KMP-ChatBackend/.github/workflows/deploy.yml` (Create)

* **Type / Scope**: Manual Production Deploy Workflow
* **Proposed Changes**: Define `workflow_dispatch`-only workflow accepting required `image_tag` input (validated against
  `^sha-[0-9a-f]{7,40}$`), `concurrency: { group: prod-deploy, cancel-in-progress: false }`, connecting to
  `nerochat-prod` via GitHub Environment SSH secrets and invoking the forced-command deploy key with `image_tag`.

##### 16. `KMP-ChatBackend/app/src/test/kotlin/io/github/neronguyenvn/nerochat/api/security/ProdProfileHardeningTest.kt` (Create) & `ChatWebSocketHandlerTest.kt` (Modify)

* **Type / Scope**: Backend Automated Verification Tests
* **Proposed Changes**:
    * Add `ProdProfileHardeningTest` activating `prod` profile (with H2/mocked backing beans) asserting `/v3/api-docs`
      and `/swagger-ui/index.html` are disabled (`404`/blocked), Hikari `minimum-idle == 0`,
      `chat.events.publish-enabled == false`, and internal Actuator liveness probe returns `200` `{"status":"UP"}`.
    * Add a test case in `ChatWebSocketHandlerTest` asserting a session whose last pong is 35 seconds old (> 30s, < 70s)
      stays open on `pingClients()`, while one older than 70 seconds closes with `SESSION_NOT_RELIABLE`.

##### 17. `KMP-ChatBackend/docs/designs/gcp-managed-free-tier-deployment.md` (Create), `docs/DEPLOYMENT.md` (Modify), `docs/README.md` (Modify), `app/README.md` (Modify), `chat/README.md` (Modify)

* **Type / Scope**: Governance Documentation & Module Contracts
* **Proposed Changes**:
    * Save this finalized EGM design doc under `KMP-ChatBackend/docs/designs/gcp-managed-free-tier-deployment.md` and
      register both the design doc and `docs/DEPLOYMENT.md` in `KMP-ChatBackend/docs/README.md`.
    * Rewrite `KMP-ChatBackend/docs/DEPLOYMENT.md` as the concise GCP `e2-micro` + Managed Services operational runbook
      (VM swap/firewall/airlock setup, Neon direct-boot -> `-pooler` switch, disposable DB reset command, DuckDNS +
      Brevo setup, 3 AM triage commands).
    * Update `app/README.md` (Actuator port `8081`, `prod` profile invariants, Redis `maxSize`) and `chat/README.md`
      (`70_000L` pong timeout, gated `chat.events` publishing).

---

#### Part B — `Android-ChatApp`

##### 18. `Android-ChatApp/app/build.gradle.kts` (Modify)

* **Type / Scope**: Android Application Gradle Build
* **Proposed Changes**:
    * Replace hardcoded `signingConfigs.getByName("debug")` in `buildTypes.release` with a real `release` signing config
      loaded from Gradle properties / environment variables (`RELEASE_KEYSTORE_PATH`, `RELEASE_KEYSTORE_PASSWORD`,
      `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`), failing fast during release assemble tasks when credentials are
      absent (allowing a CI test-signing override only for PR compilation checks if needed, never shipping debug-signed
      GitHub Releases).
    * Wire `proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")`.
    * Parameterize `versionCode` (`-PVERSION_CODE`, default `1`) and `versionName` (`-PVERSION_NAME`, default
      `"0.1.0-local"`).

##### 19. `Android-ChatApp/app/proguard-rules.pro` (Create)

* **Type / Scope**: R8 / ProGuard Full-Mode Keep Rules
* **Proposed Changes**: Add targeted keep rules for `io.github.neronguyenvn.nerochat.contract.**` (`@Serializable`
  companions, `$$serializer`, sealed subclasses of `WsClientMessage`, `WsServerEvent`, and `ServerError`), private
  Retrofit interfaces (`RetrofitAuthNetworkApi`, `RetrofitChatNetworkApi`), Navigation 3 `@Serializable` route keys,
  `core/datastore` `UserData`, and Hilt `WorkManager` workers.

##### 20. `Android-ChatApp/core/network/build.gradle.kts` (Modify)

* **Type / Scope**: Network Library Gradle Build
* **Proposed Changes**: Normalize resolved `BASE_URL` to always end with `/`, and add a release-task configuration check
  that fails the build if `BASE_URL` is missing, points to `localhost`, or does not start with `https://`.

##### 21. `Android-ChatApp/core/network/src/main/kotlin/io/github/neronguyen/chat/core/network/di/NetworkModule.kt` (Modify)

* **Type / Scope**: OkHttp Network Configuration
* **Proposed Changes**: Increase `@BaseOkHttp` `.callTimeout(15.seconds)` to `.callTimeout(30.seconds)` so Neon
  serverless cold wake-up paired with nested `TokenAuthenticator` token refresh does not time out prematurely.

##### 22. `Android-ChatApp/app/src/main/AndroidManifest.xml` (Modify)

* **Type / Scope**: Android Application Manifest
* **Proposed Changes**: Change `android:allowBackup="true"` to `android:allowBackup="false"` so unencrypted Room SQLite
  chat history and non-transferable hardware-KeyStore DataStore tokens are not backed up to Google Drive.

##### 23. `Android-ChatApp/.github/workflows/ci.yml` (Modify) & `.github/workflows/release.yml` (Create)

* **Type / Scope**: Android CI & Signed GitHub Releases Pipeline
* **Proposed Changes**:
    * Add an R8 release compilation verification step
      (`./gradlew :app:assembleRelease -PBASE_URL=https://example.invalid/ ...`) to `ci.yml` so R8 full-mode breaks are
      caught on every PR.
    * Create `release.yml` (triggered on `v*` tag push or `workflow_dispatch`) running `./scripts/verify.sh clean`,
      decoding `ANDROID_KEYSTORE_BASE64`, building signed `assembleRelease` with `PROD_BASE_URL` and incrementing
      `VERSION_CODE`/`VERSION_NAME`, computing `.sha256`, uploading `app/build/outputs/mapping/release/mapping.txt` as a
      workflow artifact, and creating a draft GitHub Release with tester install + Brevo Spam-check instructions.

##### 24. `Android-ChatApp/app/README.md` (Modify), `core/network/README.md` (Modify), `docs/README.md` (Modify)

* **Type / Scope**: Module Contracts & Registry
* **Proposed Changes**: Document release signing/ProGuard/`allowBackup="false"` invariants in `app/README.md`, document
  `BASE_URL` normalization/HTTPS release guard and 30s `callTimeout` in `core/network/README.md`, and add a cross-repo
  reference row in `Android-ChatApp/docs/README.md` pointing to
  `KMP-ChatBackend/docs/designs/gcp-managed-free-tier-deployment.md`.

---

### Verification Plan

* **Backend Tier 2 Clean Gate (`KMP-ChatBackend`)**:
    * `./scripts/sync-archify.sh --check && ./scripts/verify.sh clean` — must pass all unit/component/Konsist tests
      including new `ProdProfileHardeningTest`, updated `ChatWebSocketHandlerTest` (70s pong timeout), and existing
      `OpenApiExportTest` (confirming non-prod OpenAPI export stays green).
    * `docker compose -f docker-compose.prod.yml config` — must validate cleanly with zero syntax or variable errors.
* **Backend Container & Airlock Rollback Drill (Live on `nerochat-prod`)**:
    * Trigger `ci.yml` push build on `main` and confirm immutable `ghcr.io/<owner>/kmp-chatbackend:sha-<short>`
      (`linux/amd64`) publishes cleanly.
    * Run one-time Neon direct-URL bootstrap (`JPA_DDL_AUTO=update`, `JPA_CREATE_NAMESPACES=true`), then flip `.env`
      permanently to `-pooler` + `JPA_DDL_AUTO=validate`.
    * Trigger `deploy.yml` with a healthy tag -> assert all 4 smoke checks pass (`/liveness` UP, `/readiness` UP,
      `GET https://$DOMAIN/api/chat/rooms` -> `401` `CommonServerError.Unauthorized`,
      `GET https://$DOMAIN/v3/api-docs` -> `404`, `RestartCount == 0`) and `docker stats --no-stream` stays within
      physical RAM without swap thrashing.
    * **Rollback drill**: Trigger `deploy.yml` with an intentionally failing image/config -> assert
      `/usr/local/bin/nerochat-deploy` automatically restarts `prev_tag`, restores service, and fails the GitHub Actions
      job.
    * **Idle Neon check**: Leave backend idle for 15 minutes while Docker `/liveness` runs -> confirm Neon dashboard
      shows compute suspended to `0 CU`, then send a login request and confirm cold wake succeeds.
* **Android Tier 2 Clean Gate & R8 Release Build (`Android-ChatApp`)**:
    * `./scripts/sync-archify.sh --check && ./scripts/verify.sh clean` — must pass all JVM unit, Robolectric UI, Compose
      screenshot, Konsist, and lint checks.
    * `./gradlew :app:assembleRelease -PBASE_URL=https://example.invalid/ ...` — must compile and minify cleanly under
      R8 full mode; verify missing/non-HTTPS `BASE_URL` fails the release build as expected.
* **Live End-to-End Signed Release APK Smoke Test (Before Publishing Draft Release)**:
    * Install the signed R8 APK built from `release.yml` on a real Android device/emulator pointing to
      `https://<name>.duckdns.org/`.
    * Complete CUJ 1 end-to-end: register -> confirm Brevo OTP arrives (check inbox + Spam + Brevo logs) -> verify
      OTP -> login -> search peer -> send and receive `wss://` messages for > 2 minutes without 30s disconnects ->
      force-close and reopen app (confirm Navigation 3 + Room restoration).
    * Complete CUJ 4: install a second signed release APK with incremented `versionCode` over the first and confirm
      in-place upgrade succeeds with local chat history preserved.

--------------------------------------------------------------------------------

*Drafted with EGM*
