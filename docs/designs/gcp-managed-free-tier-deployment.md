# $0 Hybrid Free-Tier Production Deployment & Live Release Pipeline

| Field             | Value                                                                                                                                                                                                        |
|:------------------|:-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **Author**        | Nero Nguyen                                                                                                                                                                                                  |
| **Status**        | Draft                                                                                                                                                                                                        |
| **Date**          | 2026-11-26                                                                                                                                                                                                   |
| **Linear Ticket** | [CHAT-26](https://linear.app/neronguyen/issue/NER-26/deploy-kmp-chatbackend-and-backing-services-on-dollar0-free-tier-stack) *(workspace key `NER-26`; canonical repo key `CHAT-26` per `GOVERNANCE.md` §5)* |
| **Target Repos**  | `KMP-ChatBackend` (primary owner), `Android-ChatApp` (release client pipeline)                                                                                                                               |

--------------------------------------------------------------------------------

## Overview

This design specifies the `$0/month` production deployment architecture, CI/CD release pipeline, and signed Android
distribution path for running `KMP-ChatBackend` and `Android-ChatApp` live over `https://` and `wss://` for Nero and one
external tester.

The backend runs on an already-provisioned **GCP Always Free `e2-micro`** VM (`us-west1-b`, Oregon, `x86_64`, 1 GB RAM +
2 GB swap, 30 GB `pd-standard` boot disk, `PREMIUM` network tier, Ubuntu 26.04 Minimal LTS). Three memory-capped
containers share a private Docker bridge: **Caddy** (DuckDNS + automatic Let's Encrypt HTTPS/WSS), **Spring Boot**
(pre-built `linux/amd64` image pulled from GitHub Container Registry `ghcr.io`), and a **24 MB persistence-disabled
local Redis** container for ephemeral OTP, rate-limit, and cache state.

Durable storage, messaging, and email run on US-West free-tier managed services: **Neon Postgres** (`us-west-2`),
**CloudAMQP Little Lemur** (`us-west-2`), and **Brevo SMTP**. GitHub Actions verifies and publishes immutable backend
images on push to `main`, while live VM deploys run via manual `workflow_dispatch` through a restricted SSH deploy key
with automatic local rollback. `Android-ChatApp` ships a keystore-signed, R8-minified release APK via GitHub Releases.

## The Problem

Although `KMP-ChatBackend` is containerized (`CHAT-25`) and both repositories pass local JVM and AVD E2E checks, four
gaps prevent a reliable `$0/month` live launch on the provisioned GCP `e2-micro` VM:

1. **Compute, Memory, and Build Mismatch on a 1 GB VM**: The checked-in `docker-compose.prod.yml` builds the Gradle fat
   JAR (`-Xmx2048m`) directly on the VM, requires a GitHub PAT (`GITHUB_TOKEN`) stored on disk to resolve
   `KMP-ChatContract` from GitHub Packages, hardcodes a local `postgres` JDBC URL, and co-locates PostgreSQL 17,
   RabbitMQ 4, Redis, Spring Boot, and Caddy with no container memory caps. On a 1 GB RAM `e2-micro` backed by a ~
   22-IOPS `pd-standard` disk, either building from source or running all five services causes Linux OOM kills and swap
   thrashing.
2. **Free-Tier Quota, Protocol, and Transit-Security Traps**:
  - `application.yaml` hardcodes HikariCP `minimum-idle: 5` and `tcpKeepAlive: true` without environment overrides. Five
    persistent idle connections block Neon's 5-minute idle auto-suspend and consume ~182+ CU-hours/month against Neon's
    100 CU-hours/month free ceiling, while omitting `prepareThreshold=0` causes prepared-statement collisions under
    Neon's PgBouncer (`-pooler`) transaction pooling.
  - `application.yaml` sets `default-requeue-rejected: true` with no Dead-Letter Queue. Any Brevo SMTP failure (bounce,
    unverified sender, or daily quota hit) triggers an infinite requeue loop that exhausts CloudAMQP's 1M messages/month
    quota, Brevo's 300 emails/day limit, and GCP's 1 GB/month free egress allowance.
  - Offloading Redis to Redis Cloud Free (30 MB) omits TLS (`rediss://`), sending Redis passwords, OTP hashes, and
    cached chat messages in plaintext across public GCP-to-AWS hops while adding WAN latency to every IP-rate-limited
    call.
3. **Production Security, Health Probe, and WebSocket Jitter Gaps**:
  - `application-prod.yaml` does not disable SpringDoc (`/v3/api-docs`, `/swagger-ui`). Once Swagger is disabled in
    `prod`, zero unauthenticated `GET` endpoints return `200 OK` for deploy verification; conversely, running periodic
    Actuator checks against Postgres defeats Neon auto-suspend.
  - `ChatWebSocketHandler.kt` sets `PING_INTERVAL_MS = 30_000L` equal to `PONG_TIMEOUT_MS = 30_000L` and checks timeouts
    before sending the next ping. Any CPU scheduling jitter on `e2-micro` or WAN round-trip latency closes healthy
    WebSocket connections every 30 seconds (`SESSION_NOT_RELIABLE`).
4. **Untested Android Release Packaging**: `Android-ChatApp/app/build.gradle.kts` hardcodes `signingConfig = debug`,
   static `versionCode = 1`, and enables R8 minification (`isMinifyEnabled = true`) with zero custom
   `proguard-rules.pro` for pure-JVM `KMP-ChatContract` serialization classes, while `ChatWebSocketClient.kt` silently
   swallows JSON decode errors. `core/network/build.gradle.kts` falls back to `"http://localhost/"` without
   trailing-slash normalization, `AndroidManifest.xml` permits unencrypted Room backups (`allowBackup="true"` plus
   Android 12+ `data_extraction_rules.xml`), and OkHttp's 15-second `callTimeout` can expire during a Neon cold wake-up
   paired with a token refresh retry.

## Goals

* **G1 — `$0/month` GCP Always Free Compliance**: Run the live backend on the existing `us-west1-b` `e2-micro` VM
  (`30 GB pd-standard`, `PREMIUM` network tier, 2 GB swapfile, `$1.00` billing budget alert), capping container memory
  inside 1 GB physical RAM and dropping bare-IP / unknown-`Host` scanner traffic at Caddy.
* **G2 — Quota-Safe & Private Backing Services**: Stay within Neon's 100 CU-hours/month cap via idle-draining Hikari
  pools and PgBouncer-safe JDBC settings; keep OTP, rate-limit, and cache state private on the local Docker bridge
  inside a 24 MB persistence-disabled Redis container; stop RabbitMQ infinite retry loops on CloudAMQP AMQPS (`5671`);
  and send live OTP emails via Brevo SMTP.
* **G3 — Hardened Edge, Split Health Probes & Stable WebSockets**: Disable SpringDoc in `prod` and block
  Swagger/Actuator paths at Caddy (`404`); enforce Caddy `X-Real-IP` overwrite on a pinned Docker bridge subnet trusted
  by `TrustedProxyFilter` (the backend servlet filter validating proxy IPs); expose Actuator `/liveness` (zero-I/O) and
  `/readiness` (DB/Redis/RabbitMQ) on existing port `8080` (unpublished on host); and raise `PONG_TIMEOUT_MS` to
  `70_000L`.
* **G4 — Gated GHCR Pipeline & Manual Deploy with Rollback**: Publish immutable `linux/amd64` GHCR images (public
  package visibility) gated on `./scripts/verify.sh clean` on push to `main` with no Gradle toolchain or GitHub PAT on
  the VM, and execute live deploys via manual `workflow_dispatch` through a forced-command SSH wrapper with local
  rollback.
* **G5 — Signed, Upgradeable, R8-Verified Android GitHub Releases**: Wire GitHub Secrets release keystore signing,
  explicit `app/proguard-rules.pro` keep rules, deterministic CI R8 symbol verification, HTTPS + trailing-slash
  `BASE_URL` guards, monotonic `versionCode`/`versionName`, disabled Room/DataStore backup across all Android API
  levels, raised OkHttp `callTimeout`, and draft GitHub Releases with archived `mapping.txt`.
* **G6 — Aligned Governance & Documentation**: Track all branches, commits, PRs, and docs under canonical ticket
  `CHAT-26`; keep this Elephant-and-Goldfish Model (EGM) design spec in `KMP-ChatBackend/docs/designs/` (primary infra
  owner), registered in both `KMP-ChatBackend/docs/README.md` and `Android-ChatApp/docs/README.md`; rewrite
  `KMP-ChatBackend/docs/DEPLOYMENT.md` as the matching runbook; and update impacted module `README.md` contracts. The
  Archify (architecture diagram toolchain) `[PLANNED]` -> `[AS-IS]` flow gate (`GOVERNANCE.md` §2.2) does not apply
  because `CHAT-26` introduces no new wire protocol or domain state lifecycle, and logical C4 `.archify.json` diagrams
  remain vendor-agnostic.

## Non-Goals

* **NG1 — Google Play Distribution**: No Play Console registration, Play App Signing, store listing, or 12-tester
  closed-testing tracks.
* **NG2 — Schema Migration Governance & Managed Backups**: Flyway SQL migrations (`CHAT-10`) and automated Postgres
  backup/PITR schedules are out of scope; Neon schemas remain disposable until `CHAT-10` lands.
* **NG3 — Horizontal Scaling & Full Observability Stack**: Multi-replica WebSocket fan-out (`CHAT-11`), Micrometer
  metrics/MDC distributed tracing (`CHAT-19`), and Transactional Outbox (`CHAT-16`) remain deferred.
* **NG4 — Remaining Wave 2 (Hardening & Bugfixes) Items**: `CHAT-5` (offline room-cache poisoning), `CHAT-6` (room-leave
  FK cascade and `VARCHAR(255)` limit), and `CHAT-7` (forced logout on transient refresh errors) stay in Wave 2; only
  the `PONG_TIMEOUT_MS` heartbeat fix and backup disablement are pulled into `CHAT-26`.
* **NG5 — Zero-Downtime Blue-Green Cutovers**: Running two Spring Boot JVMs concurrently is impossible within 1 GB RAM;
  a ~15–25s single-container restart window during manual deploys is accepted.

## Open Questions

* **Live Brevo Single-Sender Gmail Deliverability**: Empirical validation against the external tester's real inbox is
  required during Phase 5 launch smoke testing (see *Risks & Mitigations* for the bounce fallback).

## Critical User Journeys (CUJs)

### CUJ 1: First-Time Live Launch & External Tester Onboarding

* **Given**: The GCP `e2-micro` VM (`nerochat-prod`) runs Caddy, Spring Boot (`prod` profile), and 24 MB local Redis;
  DuckDNS resolves `<name>.duckdns.org` to `<VM_PUBLIC_IP>`; Neon schemas are bootstrapped; CloudAMQP and Brevo
  credentials are configured in `.env`; and a signed R8 `Android-ChatApp` APK built with
  `BASE_URL=https://<name>.duckdns.org/` is published on GitHub Releases.
* **When**: Nero and one external tester install the release APK, register two accounts, retrieve their 6-digit OTPs
  from Brevo email delivery, verify accounts, log in, search for each other, and exchange real-time chat messages for >
  2 minutes.
* **Then**: Caddy serves valid Let's Encrypt TLS for both `https://` and `wss://`, OTP emails arrive and verify,
  messages transition `SENDING` -> `SENT` and arrive live over WebSocket without 30-second ping-timeout disconnects, and
  `https://<name>.duckdns.org/v3/api-docs` returns `404`.

### CUJ 2: Controlled Production Release & Automatic Rollback

* **Given**: A verified commit is merged to `KMP-ChatBackend/main`, GitHub Actions has passed
  `./scripts/verify.sh clean`, and `ghcr.io/<owner>/kmp-chatbackend:sha-<short>` is published while the previous
  known-good image tag is running on `nerochat-prod`.
* **When**: Nero triggers the manual `workflow_dispatch` deploy workflow in GitHub Actions targeting `sha-<short>`
  (either a healthy release or a drill with an intentionally broken image).
* **Then**: GitHub Actions invokes the forced-command SSH wrapper (`/usr/local/bin/nerochat-deploy sha-<short>`) without
  shell access or `.env` exposure. The VM pulls the new image, saves the previous tag, swaps the backend container,
  verifies local `/liveness`, `/readiness`, Caddy TLS (`--resolve`), `401`/`404` edge rules, and `RestartCount == 0`,
  then prunes stale images while keeping current + previous tags. If any local check fails, the VM restarts the locally
  cached previous tag and fails the CI job. After SSH returns, `deploy.yml` verifies public-DNS HTTPS reachability from
  the runner.

### CUJ 3: Idle Cost Conservation & Cold-Wake Recovery

* **Given**: Both users are inactive for more than 10 minutes while the VM stays online running Caddy, Spring Boot, and
  Docker's periodic `/actuator/health/liveness` check.
* **When**: Idle HikariCP connections drain to zero (`minimum-idle: 0`), Neon auto-suspends compute to 0 CU, and a user
  subsequently opens `Android-ChatApp` to log in or refresh their access token.
* **Then**: Docker liveness checks never wake Neon while idle; the incoming request wakes Neon automatically within the
  configured Hikari (`20s`) and OkHttp (`35s`) timeouts without PgBouncer prepared-statement errors, and monthly Neon
  usage remains within the 100 CU-hours/month free-tier limit.

### CUJ 4: In-Place Android Release Upgrade

* **Given**: The external tester already has `v0.1.0` (`versionCode = 1`) of the signed R8 release APK installed with
  active login state and local Room chat history.
* **When**: A new tag (`v0.2.0`) is pushed to `Android-ChatApp`, GitHub Actions signs the R8 release APK with the same
  keystore and incremented `versionCode`, publishes it to GitHub Releases (archiving `mapping.txt`), and the tester
  installs the new APK over the existing app.
* **Then**: Android performs an in-place upgrade without `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, preserving local Room
  history and encrypted DataStore tokens, and connects over HTTPS/WSS.

--------------------------------------------------------------------------------

## Technical Plan (Architecture)

### 1. System Topology & Network Boundaries

Production runs three containers on the existing GCP Always Free `e2-micro` (`us-west1-b`, Oregon) inside a pinned
private Docker bridge subnet (`172.28.0.0/24`), talking over TLS to US-West managed services (`us-west-2`, Oregon) and
Brevo SMTP:

```text
Android-ChatApp (Signed R8 Release APK, GitHub Releases)
  │  HTTPS (:443) + WSS (/ws/chat)
  │  DNS: <name>.duckdns.org -> <VM_PUBLIC_IP>
  ▼
┌──────────────────────────────────────────────────────────────────────┐
│ GCP Always Free e2-micro (us-west1-b, 1 GB RAM + 2 GB Swap, 30 GB)   │
│ Inbound firewall: TCP 80, 443, 22 (key-only + forced-command CI key) │
│                                                                      │
│  ┌────────────────────────────────────────────────────────────────┐  │
│  │ Docker Bridge: nerochat_internal (pinned 172.28.0.0/24)        │  │
│  │                                                                │  │
│  │  Caddy (:80/:443, mem 96m)                                     │  │
│  │   - Auto Let's Encrypt TLS for {$DOMAIN} (ACME HTTP-01 safe)   │  │
│  │   - Aborts unknown Host / bare IP; blocks /v3/api-docs,        │  │
│  │     /swagger-ui, and /actuator with 404                        │  │
│  │   - Overwrites header_up X-Real-IP {remote_host}               │  │
│  │         │ HTTP (:8080, bridge only — never exposed on host)    │  │
│  │         ▼                                                      │  │
│  │  Spring Boot Backend (ghcr.io/...:sha-<short>, mem 550m)       │  │
│  │   - Single Tomcat :8080 (API + /actuator/health/{liveness,     │  │
│  │     readiness} permitted for container loopback)               │  │
│  │   - Profile: prod | PONG_TIMEOUT_MS = 70_000L                  │  │
│  │         │ RESP (:6379, bridge only)                            │  │
│  │         ▼                                                      │  │
│  │  Local Ephemeral Redis (redis:7-alpine, mem 48m, cap 24mb)     │  │
│  │   - No host port, no disk persistence, volatile-lru            │  │
│  └─────────┼──────────────────────────────────────────────────────┘  │
└────────────┼─────────────────┬────────────────────────┬──────────────┘
             │ JDBC + TLS      │ AMQPS (:5671)          │ SMTP (:587 STARTTLS)
             ▼                 ▼                        ▼
      Neon Postgres     CloudAMQP Little Lemur      Brevo SMTP
      (us-west-2,       (us-west-2, 1M/mo,          (300 emails/day,
       -pooler,          vhost=<username>,           verified sender)
       min-idle=0)       requeue=false)

GitHub Actions (public repos, $0)
  - KMP-ChatBackend push -> verify.sh clean -> build & push linux/amd64 to public GHCR
  - KMP-ChatBackend workflow_dispatch -> restricted SSH deploy + public DNS smoke check
  - Android-ChatApp tag/dispatch -> verify.sh clean -> signed R8 APK -> GitHub Releases
```

### 2. Compute, Memory & Disk Budget (`e2-micro` 1 GB RAM + 30 GB `pd-standard`)

Every container, thread pool, and log stream is explicitly bounded to stay inside 1 GB physical RAM without swap
thrashing (target budget verified via `docker stats` and `free -m` on `nerochat-prod`):

| Layer / Component                               | Memory Limit                              | Runtime Caps & Hygiene                                                                                                                         |
|:------------------------------------------------|:------------------------------------------|:-----------------------------------------------------------------------------------------------------------------------------------------------|
| Ubuntu 26.04 Minimal + `dockerd` / `containerd` | ~220–260 MB                               | 2 GB swapfile (`vm.swappiness=10`) as emergency cushion                                                                                        |
| `caddy` (`caddy:2-alpine`)                      | `mem_limit: 96m`                          | Named volumes `caddy_data` & `caddy_config`; `json-file` logs `10m x 3`                                                                        |
| `redis` (`redis:7-alpine`)                      | `mem_limit: 48m`                          | `--maxmemory 24mb --maxmemory-policy volatile-lru --save "" --appendonly no`                                                                   |
| `backend` (`eclipse-temurin:21-jre-alpine`)     | `mem_limit: 550m` (`memswap_limit: 750m`) | SerialGC + bounded heap/metaspace/direct/code-cache/stack flags summing under `550m`; bounded Tomcat, Redisson, and RabbitMQ pools             |
| Disk Hygiene (30 GB `pd-standard`)              | < 10 GB steady                            | Docker `json-file` `10m x 3` on all containers; deploy script prunes stale images after healthy cutover while retaining current + previous tag |

### 3. Managed Backing Services & Quota Invariants

* **Neon Postgres (`us-west-2`, 100 CU-hours/month, 5-min auto-suspend)**:
  * Parameterize HikariCP in `application.yaml` and configure `application-prod.yaml` with `minimum-idle: 0`,
    `maximum-pool-size: 4`, `idle-timeout: 60000`, `max-lifetime: 300000`, `connection-timeout: 20000`,
    `keepalive-time: 0`, and `tcpKeepAlive: false` so idle sockets drain and Neon scales to zero.
  * Runtime `POSTGRES_URL` uses Neon's `-pooler` endpoint with
    `?sslmode=require&prepareThreshold=0&preparedStatementCacheQueries=0` to avoid PgBouncer transaction-mode
    prepared-statement collisions.
  * One-time initial schema creation (`user_service` and `chat_service` before Flyway `CHAT-10`) runs once against
    Neon's **direct** (non-pooler) URL with `JPA_DDL_AUTO=update` and `JPA_CREATE_NAMESPACES=true`, then switches
    permanently to `-pooler` with `JPA_DDL_AUTO=validate` and `JPA_CREATE_NAMESPACES=false`.
* **Local Ephemeral Redis (`redis:7-alpine`, private bridge)**:
  * Serves `RedissonOtpService` (180s TTL), `IpRateLimitingService` / `EmailRateLimitingService`, and
    `CacheNames.MESSAGES` (30m TTL) over the private `172.28.0.0/24` bridge with no host port.
  * Bound `CacheNames.MESSAGES` in `RedisConfig.kt` with `maxSize = 200` so cached slices cannot evict active OTPs
    inside the 24 MB cap.
  * Pass `REDIS_POOL_SIZE=4`, `REDIS_SUB_POOL_SIZE=2`, `REDIS_THREADS=2`, and `REDIS_NETTY_THREADS=4` via
    `docker-compose.prod.yml` `environment:` (feeding the multi-line `spring.redis.redisson.config` block in
    `application.yaml`).
* **CloudAMQP Little Lemur (`us-west-2`, 1M msgs/mo, 20 conns)**:
  * Uses AMQPS (`RABBITMQ_PORT=5671`, `RABBITMQ_SSL_ENABLED=true`, already parameterized in `application.yaml`) and
    `RABBITMQ_VHOST=<cloudamqp-8-char-username>`.
  * Set `spring.rabbitmq.listener.simple.default-requeue-rejected: false` in `application.yaml` so permanently failing
    SMTP jobs stop after 3 exponential retries instead of looping infinitely (users tap Resend on transient failure;
    full DLX/DLQ is tracked in `CHAT-16`).
* **Brevo SMTP (`smtp-relay.brevo.com`, 300 emails/day)**:
  * Uses port `587` with `STARTTLS` (`SMTP_USERNAME` = Brevo SMTP login, `SMTP_SENDER` = verified single-sender address;
    already parameterized in `application.yaml`).

### 4. Edge Security, Single-Port Split Probes & WebSocket Stability

* **Caddy Edge (`Caddyfile`)**:
  * Serves `{$DOMAIN}` with `reverse_proxy backend:8080 { header_up X-Real-IP {remote_host} }`, structured stdout access
    logs, and explicit `respond 404` blocks for `/v3/api-docs*`, `/swagger-ui*`, and `/actuator*`.
  * Aborts unmatched/bare-IP traffic while preserving `{$DOMAIN}` ACME HTTP-01 (`/.well-known/acme-challenge/*`) on
    `:80` so scanner traffic does not consume GCP's 1 GB/month free egress allowance (excluding China and Australia).
* **Spring Security, Swagger & Proxy Trust**:
  * Pin `nerochat_internal` subnet to `172.28.0.0/24` (inside `TrustedProxyFilter`'s `172.16.0.0/12` CIDR;
    `127.0.0.1/32` is already trusted in `application.yaml:109`).
  * Disable SpringDoc in `application-prod.yaml` (`springdoc.api-docs.enabled: false`,
    `springdoc.swagger-ui.enabled: false`); Caddy enforces `404` at the edge without duplicating profile/security
    branches in `OpenApiConfig.kt` or `SecurityConfig.kt`.
* **Split Actuator Probes on Existing Port `8080` (Single Tomcat, Zero-I/O Liveness vs. Deploy-Only Readiness)**:
  * Add `spring-boot-starter-actuator` served on the existing port `8080` (avoiding the memory cost of a second embedded
    Tomcat server). Port `8080` is unpublished on the host and `/actuator*` is blocked with `404` by Caddy.
  * Permit `/actuator/health` and `/actuator/health/**` in `SecurityConfig.kt`.
  * Configure `management.endpoint.health.probes.enabled: true` and
    `management.endpoint.health.group.readiness.include: readinessState,db,redis,rabbit`.
  * Container `HEALTHCHECK` polls `http://127.0.0.1:8080/actuator/health/liveness` (zero DB/Redis/MQ I/O so Neon stays
    asleep). Deploy smoke checks call `http://127.0.0.1:8080/actuator/health/readiness` **once** via `docker exec` to
    verify Neon, Redis, and CloudAMQP connectivity.
* **WebSocket Heartbeat Fix**:
  * Update `PONG_TIMEOUT_MS` in `ChatWebSocketHandler.kt` from `30_000L` to `70_000L` (> 2x
    `PING_INTERVAL_MS = 30_000L`) and make `pingClients()` `internal` with an injectable clock lambda for deterministic
    unit testing.

### 5. CI/CD Pipeline, Forced-Command SSH Wrapper & Automatic Local Rollback

1. **Automated Test & Public GHCR Image Publish (`KMP-ChatBackend/.github/workflows/ci.yml`)**:
  * Runs `./scripts/sync-archify.sh --check` and `./scripts/verify.sh clean` on PRs and pushes to `main`.
  * On push to `main`, builds `linux/amd64` Docker image using BuildKit secrets (`GITHUB_ACTOR`, `GITHUB_TOKEN`, needed
    to resolve `KMP-ChatContract` from GitHub Packages) and pushes immutable
    `ghcr.io/<owner>/kmp-chatbackend:sha-<short>` and `:main` (lowercase `<owner>`).
  * **GHCR Prerequisite**: Set the `ghcr.io/<owner>/kmp-chatbackend` package visibility to **Public** once in GitHub
    Packages so the VM executes `docker pull` without storing any GitHub PAT.
2. **Manual Deploy (`KMP-ChatBackend/.github/workflows/deploy.yml`)**:
  * Triggered only via `workflow_dispatch` with input `image_tag` (regex `^sha-[0-9a-f]{7,40}$`),
    `concurrency.cancel-in-progress: false`, using GitHub Environment `production` secrets (`DEPLOY_HOST`,
    `DEPLOY_USER`, `DEPLOY_SSH_KEY`, `DEPLOY_SSH_KNOWN_HOSTS`).
  * Connects via a dedicated CI SSH key restricted in `~/.ssh/authorized_keys` to
    `command="/usr/local/bin/nerochat-deploy",no-port-forwarding,no-X11-forwarding,no-agent-forwarding,no-pty`,
    preventing interactive shell access or `.env` exfiltration.
  * After SSH returns, runs a runner-side public-DNS HTTPS check against `https://$DOMAIN/api/chat/rooms` (`401` +
    `CommonServerError.Unauthorized`) and `/v3/api-docs` (`404`) so DuckDNS blips fail CI visibly without spuriously
    rolling back a healthy backend.
3. **Single-Container VM Cutover & Automatic Local Rollback (`/usr/local/bin/nerochat-deploy`)**:
  * VM-resident files (`/opt/nerochat/{docker-compose.prod.yml,Caddyfile,.env}` and `/usr/local/bin/nerochat-deploy`)
    are provisioned and updated via admin SSH per `docs/DEPLOYMENT.md`.
  * Script enforces no `set -x` and no `docker compose config` stdout output (preventing `.env` leakage into public
    GitHub Actions logs), loads `/opt/nerochat/.env`, validates `$SSH_ORIGINAL_COMMAND`, pulls the target tag, records
    the prior tag in `.deploy-state/prev_tag`, persists `IMAGE_TAG=<tag>` into `.deploy-state/current.env`, and swaps
    `backend`.
  * Runs 5 local rollback-gating checks (capturing non-zero `wget`/`curl` exits without `set -e` aborting rollback):
    1. Poll container `http://127.0.0.1:8080/actuator/health/liveness` -> `UP` (90s budget).
    2. One-shot container `http://127.0.0.1:8080/actuator/health/readiness` -> `UP` (Neon + Redis + CloudAMQP).
    3. Local Caddy TLS + proxy check via `curl -sS --resolve "$DOMAIN:443:127.0.0.1" https://$DOMAIN/api/chat/rooms` ->
       `401` with `CommonServerError.Unauthorized` (validates Let's Encrypt SNI cert, `X-Real-IP`, `TrustedProxyFilter`,
       `SecurityConfig`, and `ContractJson`, the shared `kotlinx.serialization` JSON instance, without DuckDNS
       dependency or hairpin egress).
    4. Local Caddy Swagger/Actuator block check via `curl --resolve` on `/v3/api-docs`, `/swagger-ui/index.html`, and
       `/actuator/health/liveness` -> `404`.
    5. Assert container `RestartCount == 0`.
  * On success: prunes older backend images while keeping current + `prev_tag`. On failure: if `prev_tag` exists
    (skipping rollback on first-ever boot), restores `prev_tag` from local disk, re-verifies `/liveness`, and exits
    non-zero.

### 6. Android Signed R8 Release Architecture (`Android-ChatApp`)

* **Keystore Custody & Signing (`app/build.gradle.kts`)**:
  * Replace debug release signing with a `release` signing config reading `RELEASE_KEYSTORE_PATH`,
    `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS`, and `RELEASE_KEY_PASSWORD` via `providers.gradleProperty` /
    `providers.environmentVariable` (preserving `org.gradle.configuration-cache=true`), failing fast whenever a release
    assemble/bundle task runs without credentials.
  * Store the `.jks`/`.p12` backup offline in a password manager and base64-encoded in GitHub Secrets
    (`ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`).
* **Monotonic Versioning**: Parameterize `VERSION_CODE` and `VERSION_NAME` via `providers.gradleProperty`, defaulting
  locally to `1` / `"0.1.0-local"`.
* **Network & Storage Hardening (`core/network/build.gradle.kts`, `NetworkModule.kt`, `ChatWebSocketClient.kt`,
  `AndroidManifest.xml`)**:
  * Normalize `BASE_URL` trailing `/` and enforce release-only HTTPS validation via
    `androidComponents.onVariants(selector().withBuildType("release"))`, rejecting blank, non-`https://`, `localhost`,
    `127.0.0.1`, and `10.0.2.2`.
  * Increase `@BaseOkHttp` `callTimeout` in `NetworkModule.kt` from `15.seconds` to `35.seconds` (exceeding backend
    `HIKARI_CONNECTION_TIMEOUT=20000` + WAN RTT; `@RealtimeOkHttp` preserves `callTimeout(0)`).
  * Log non-fatal WebSocket JSON decode exceptions in `ChatWebSocketClient.kt` instead of silently swallowing them.
  * Set `android:allowBackup="false"`, remove `android:dataExtractionRules` and `android:fullBackupContent` from
    `AndroidManifest.xml`, and delete the two boilerplate backup XML files so Room SQLite and DataStore are excluded on
    Android 12+ (`targetSdk = 37`).
* **R8 Keep Rules & Release CI (`app/proguard-rules.pro`, `ci.yml`, `release.yml`)**:
  * Keep `KMP-ChatContract` `@Serializable` models/sealed hierarchies, Retrofit service interfaces, Navigation 3 route
    keys, `UserData`, and Hilt workers.
  * In `ci.yml`, generate an ephemeral PKCS12 keystore via `keytool`, run `:app:assembleRelease` against
    `https://example.invalid/`, and assert `io.github.neronguyenvn.nerochat.contract` WebSocket/error `$$serializer`
    symbols survived R8 in `mapping.txt` / `seeds.txt`.
  * In `release.yml` (`v*` tag push or `workflow_dispatch`), run `./scripts/verify.sh clean`, decode the release
    keystore secret, build signed `assembleRelease` against `vars.PROD_BASE_URL` with monotonic `VERSION_CODE`
    (`github.run_number`) and `VERSION_NAME` (tag semver or dispatch input), archive `mapping.txt`, and create a draft
    GitHub Release with tester install + Brevo Spam-check notes.

--------------------------------------------------------------------------------

## Alternatives Considered

### Alternative A: OCI Always Free ARM (4 OCPU / 24 GB RAM, All-in-One Docker Compose)

* **Pros**: 24 GB RAM allows co-locating PostgreSQL 17, Redis 7, RabbitMQ 4, Spring Boot, and Caddy on a single host
  with zero external database or broker accounts.
* **Cons**: Singapore (`ap-singapore-1`) Always Free Ampere A1 capacity is frequently exhausted unless upgrading to
  Pay-As-You-Go or retrying for available OCI Ampere A1 capacity.
* **Reason for Rejection**: A GCP Always Free `e2-micro` VM (`us-west1-b`, `30 GB pd-standard`) is already provisioned;
  adapting the topology to GCP + US-West managed services avoids waiting on OCI regional capacity.

### Alternative B: Co-Locating PostgreSQL 17, RabbitMQ 4, Redis, Spring Boot & Caddy on GCP `e2-micro`

* **Pros**: Reuses the existing `docker-compose.prod.yml` all-in-one layout without external Neon or CloudAMQP accounts.
* **Cons**: Spring Boot JVM + PostgreSQL 17 + RabbitMQ 4 (Erlang VM) + Redis + Caddy exceed 1 GB physical RAM
  immediately and thrash the ~22-IOPS `pd-standard` swap disk.
* **Reason for Rejection**: Physically infeasible within 1 GB RAM without OOM kills or severe swap thrashing.

### Alternative C: Managed Redis Cloud Free (30 MB / 30 Connections, Plain TCP)

* **Pros**: Keeps the VM stateless across reboots and saves ~35–48 MB of local VM RAM.
* **Cons**: Redis Cloud's free 30 MB tier omits TLS (`rediss://`), exposing the Redis `AUTH` password, OTP hashes, and
  cached chat messages in plaintext across public GCP-to-AWS hops while adding WAN latency, GCP egress, and a
  30-connection / 100 ops/s cap.
* **Reason for Rejection**: Moving ephemeral OTPs, IP rate limits, and a 200-entry message cache to a 24 MB
  persistence-disabled local Redis container eliminates plaintext WAN leaks, removes one external vendor, and avoids WAN
  round-trip latency on rate-limit checks with zero code rewrite.

### Alternative D: Auto-Deploying on Every Push to `main` (Without Manual Trigger)

* **Pros**: Zero manual clicks between merging a PR and updating the live VM.
* **Cons**: Routine documentation or WIP merges restart the live backend mid-conversation for connected testers; before
  Flyway (`CHAT-10`) lands, container rollback cannot undo Hibernate `ddl-auto=update` schema changes.
* **Reason for Rejection**: Automated CI verification and immutable GHCR publishing run on push to `main`, while live
  cutovers wait for an explicit manual `workflow_dispatch` trigger until `CHAT-10` makes schema evolution versioned.

### Alternative E: Local Debug APK (`-PBASE_URL=...`)

* **Pros**: Requires zero keystore setup or ProGuard rules today.
* **Cons**: Switching later from a debug-signed APK to a release-signed APK triggers
  `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, forcing the external tester to uninstall and wipe local Room chat history, and
  leaves R8 full-mode serialization untested.
* **Reason for Rejection**: Shipping a keystore-signed R8 release APK via GitHub Releases costs `$0`, preserves in-place
  upgrades from day one, and verifies R8 keep rules early.

### Alternative F: Google Play Internal Testing

* **Pros**: Automated Play Store delivery for up to 100 testers.
* **Cons**: Requires a `$25` Play Console registration fee, identity verification, and Play Console metadata overhead
  for one external tester.
* **Reason for Rejection**: Violates the `$0` target when GitHub Releases provides direct APK distribution at zero cost.

### Alternative G: Supabase Free Tier (PostgreSQL)

* **Pros**: Full managed Postgres dashboard and tooling.
* **Cons**: Pauses the entire database project after 7 days of inactivity, requiring manual dashboard resume.
* **Reason for Rejection**: Neon wakes automatically on incoming TCP connections when Hikari `minimum-idle: 0`.

### Alternative H: Resend SMTP (Transactional Email)

* **Pros**: SMTP and HTTP API support with clear delivery logs.
* **Cons**: Requires owned-domain DNS `SPF`/`DKIM` verification to email external recipients and cannot work over
  DuckDNS.
* **Reason for Rejection**: Brevo supports single-sender SMTP without purchasing a custom domain.

### Alternative I: On-VM Docker Builds, Local Laptop Tunneling (`cloudflared`), or Wildcard DNS (`sslip.io`)

* **Pros**: On-VM builds skip GHCR setup; `cloudflared` needs no cloud VM; `<ip>.sslip.io` needs no DNS signup.
* **Cons**: Gradle (`-Xmx2048m`) OOMs a 1 GB VM and requires storing a GitHub PAT on disk; local tunneling stops
  whenever the developer's laptop sleeps; `sslip.io` embeds the raw IP in the URL and breaks if the VM IP changes.
* **Reason for Rejection**: Pre-built public GHCR images eliminate VM OOMs and host PATs, GCP `e2-micro` runs 24/7, and
  DuckDNS provides a readable subdomain (`<name>.duckdns.org`) independent of IP changes.

--------------------------------------------------------------------------------

## Non-Functional Requirements

### Security & Privacy

* **Zero Host Exposure of Internal Services**: Only TCP `80`, `443`, and key-only `22` are open on `nerochat-prod`.
  Backend (`8080`) and local Redis (`6379`) never bind public host ports; Caddy returns `404` for `/actuator*`,
  `/v3/api-docs*`, and `/swagger-ui*`.
* **Restricted CI SSH Deploy Key**: The CI SSH key is locked in `~/.ssh/authorized_keys` to
  `/usr/local/bin/nerochat-deploy` with port/agent/X11/PTY forwarding disabled, preventing shell access or `.env`
  exfiltration. No GitHub PAT is stored on the VM.
* **Transport & Client Privacy**: External traffic uses Caddy Let's Encrypt TLS (`https://`/`wss://`); Neon uses
  `sslmode=require`; CloudAMQP uses AMQPS (`5671`); Brevo uses STARTTLS (`587`); local Redis stays inside
  `172.28.0.0/24`; Android release builds enforce `https://` `BASE_URL`, `HttpLoggingInterceptor.Level.NONE`, and
  disabled backup/transfer (`allowBackup="false"`, boilerplate extraction rules removed).
* **Keystore Custody**: The Android release keystore is never committed to Git; encrypted copies live in both GitHub
  Secrets and an offline password manager.

### Cost & Quota Economics (`$0.00/month` Target)

* **GCP Always Free**: `e2-micro`, `us-west1-b`, `30 GB pd-standard`, `PREMIUM` network tier (required for the 1
  GB/month North America egress credit), external IP attached only while VM runs, `$1.00` billing budget alert enabled.
* **Neon Free**: `<= 100 CU-hours/month`, `0.5 GB` storage; enforced by `HIKARI_MIN_IDLE=0`,
  `HIKARI_TCP_KEEPALIVE=false`, and zero-I/O Docker `/liveness` probes.
* **CloudAMQP & Brevo Free**: `<= 1M msgs/month` / `20 conns` (protected by `default-requeue-rejected: false`) and
  `<= 300 emails/day`.

### Performance & Latency

* **1 GB RAM Envelope**: Target container limits (`550m` backend + `96m` Caddy + `48m` Redis = `694m`, with `backend`
  `memswap_limit: 750m`) leave ~300 MB for Ubuntu 26.04 Minimal and `dockerd`; verify steady-state RSS and zero
  sustained swap I/O via `docker stats` and `free -m` on `nerochat-prod`.
* **Cold-Wake Timeout Ordering**: `HIKARI_CONNECTION_TIMEOUT=20000` (20s server) stays below Android `@BaseOkHttp`
  `callTimeout(35.seconds)` (35s client), absorbing Neon's 1–3s wake-up plus nested token refresh retries without
  premature client aborts.

### Rollout & Rollback

* **Immutable Tag Promotion**: Every backend release is pinned by `sha-<short>` tag and persisted to
  `.deploy-state/current.env`; `deploy.yml` enforces `cancel-in-progress: false`.
* **Automatic Local Rollback**: `/usr/local/bin/nerochat-deploy` preserves `prev_tag` on local disk and restarts it
  automatically if any of the 5 local smoke checks fail (valid for backward-compatible app releases; routine deploys run
  `JPA_DDL_AUTO=validate`).

--------------------------------------------------------------------------------

## Risks & Mitigations

* **Brevo `@gmail.com` Single-Sender DMARC Rejection**: Sending OTP emails from a `@gmail.com` address through Brevo
  fails DMARC alignment and can land in Spam or hard-bounce.
  * *Mitigation*: Validate live delivery to both Nero's and the external tester's real inboxes during Phase 5 smoke
    testing; inspect Brevo transactional logs on bounce; include a "check Spam" note in GitHub Release notes; switch to
    a verified custom-domain sender if hard bounces occur.
* **Silent OTP Drop on Transient Brevo Outage (`default-requeue-rejected: false`)**: A Brevo outage lasting longer than
  the 3 exponential retries (~7s) discards the queued OTP job rather than requeuing indefinitely.
  * *Mitigation*: Acceptable because OTPs expire in 180s and users can tap Resend; bounded DLX/DLQ diagnostics are
    tracked under `CHAT-16`.
* **GCP China/Australia Scanner Egress**: Automated scanners from regions excluded from GCP's 1 GB free tier can hit
  `<VM_PUBLIC_IP>` and incur minor egress fees.
  * *Mitigation*: Configure Caddy to serve only `{$DOMAIN}` and abort unknown-`Host` / bare-IP connections immediately,
    and configure a `$1.00` GCP billing alert.
* **R8 Full-Mode Stripping**: R8 full mode can strip `KMP-ChatContract` polymorphic serializers or Retrofit signatures.
  * *Mitigation*: Add explicit `app/proguard-rules.pro` keep rules, assert `$$serializer` retention in CI after
    `assembleRelease`, log non-fatal decode failures in `ChatWebSocketClient.kt`, archive `mapping.txt`, and run a live
    device smoke test before publishing any draft GitHub Release.
* **Non-Reversible Schema Changes Before Flyway (`CHAT-10`)**: Automatic container rollback cannot undo database
  mutations made if `JPA_DDL_AUTO=update` is left enabled.
  * *Mitigation*: Restrict `JPA_DDL_AUTO=update` to the one-time initial bootstrap on Neon's direct endpoint, then lock
    `.env` to `JPA_DDL_AUTO=validate`; treat Neon schemas as disposable until `CHAT-10` lands.
* **Android Keystore Loss Locking Out Upgrades**: Losing the keystore file or passwords prevents signing compatible
  future APKs.
  * *Mitigation*: Store the keystore and credentials in both GitHub Secrets and an offline password manager before
    shipping `v0.1.0`.
* **DuckDNS Intermittent Outages or Ephemeral IP Drift**: DuckDNS occasionally has brief DNS delays, and an ephemeral VM
  IP changes on VM stop/start.
  * *Mitigation*: Persist Caddy's `caddy_data` volume (never `docker compose down -v`), decouple VM rollback checks from
    external DNS via `curl --resolve`, and either promote `<VM_PUBLIC_IP>` to an attached static IP (while VM runs) or
    install a DuckDNS systemd timer on the host.

--------------------------------------------------------------------------------

## Detailed Implementation Plan

All changes execute across two repositories under branch `infra/chat-26-gcp-managed-deployment` (canonical ticket
`CHAT-26`) without modifying `KMP-ChatContract` wire schemas. Work is partitioned into five sequential, verifiable
phases.

---

### Phase 1 — Backend Application Hardening & JVM Verification (`KMP-ChatBackend`)

#### 1. `KMP-ChatBackend/gradle/libs.versions.toml` (Modify)
* **Type / Scope**: Gradle Version Catalog
* **Proposed Changes**: Add
  `spring-boot-starter-actuator = { module = "org.springframework.boot:spring-boot-starter-actuator" }` under
  `[libraries]`.

#### 2. `KMP-ChatBackend/app/build.gradle.kts` (Modify)
* **Type / Scope**: Gradle Module Build
* **Proposed Changes**: Add `implementation(libs.spring.boot.starter.actuator)` to `:app` dependencies.

#### 3. `KMP-ChatBackend/app/src/main/resources/application.yaml` (Modify)

* **Type / Scope**: Shared Spring Boot Configuration (`RABBITMQ_*` and `SMTP_*` placeholders already exist on lines
  50–103)
* **Proposed Changes**:
  * Parameterize HikariCP: `maximum-pool-size: ${HIKARI_MAX_POOL_SIZE:10}`, `minimum-idle: ${HIKARI_MIN_IDLE:5}`,
    `connection-timeout: ${HIKARI_CONNECTION_TIMEOUT:20000}`, `idle-timeout: ${HIKARI_IDLE_TIMEOUT:300000}`,
    `max-lifetime: ${HIKARI_MAX_LIFETIME:1800000}`, `keepalive-time: ${HIKARI_KEEPALIVE_TIME:0}`, and
    `data-source-properties.tcpKeepAlive: ${HIKARI_TCP_KEEPALIVE:true}`.
  * Parameterize Redisson thread counts inside `spring.redis.redisson.config: |`: `threads: ${REDIS_THREADS:2}`,
    `nettyThreads: ${REDIS_NETTY_THREADS:4}`, and `pingConnectionInterval: 60000` inside `singleServerConfig:`.
  * Flip `spring.rabbitmq.listener.simple.default-requeue-rejected` from `true` to `false` and parameterize
    `max-concurrency: ${RABBITMQ_MAX_CONCURRENCY:3}`.

#### 4. `KMP-ChatBackend/app/src/main/resources/application-prod.yaml` (Modify)
* **Type / Scope**: Production Spring Profile Configuration
* **Proposed Changes**:
  * Configure exact Spring YAML paths for Neon scale-to-zero:
    * `spring.datasource.hikari.minimum-idle: ${HIKARI_MIN_IDLE:0}`
    * `spring.datasource.hikari.maximum-pool-size: ${HIKARI_MAX_POOL_SIZE:4}`
    * `spring.datasource.hikari.idle-timeout: ${HIKARI_IDLE_TIMEOUT:60000}`
    * `spring.datasource.hikari.max-lifetime: ${HIKARI_MAX_LIFETIME:300000}`
    * `spring.datasource.hikari.connection-timeout: ${HIKARI_CONNECTION_TIMEOUT:20000}`
    * `spring.datasource.hikari.keepalive-time: ${HIKARI_KEEPALIVE_TIME:0}`
    * `spring.datasource.hikari.data-source-properties.tcpKeepAlive: ${HIKARI_TCP_KEEPALIVE:false}`
  * Cap Tomcat and RabbitMQ pools: `server.tomcat.threads.max: 16`, `server.tomcat.threads.min-spare: 4`,
    `spring.rabbitmq.listener.simple.max-concurrency: ${RABBITMQ_MAX_CONCURRENCY:1}`.
  * Disable public OpenAPI/Swagger: `springdoc.api-docs.enabled: false`, `springdoc.swagger-ui.enabled: false`.
  * Configure single-port Actuator probes on existing port `8080`: `management.endpoints.web.exposure.include: health`,
    `management.endpoint.health.probes.enabled: true`, `management.endpoint.health.show-details: never`,
    `management.endpoint.health.group.readiness.include: readinessState,db,redis,rabbit`.

#### 5. `KMP-ChatBackend/app/src/main/kotlin/io/github/neronguyenvn/nerochat/api/security/SecurityConfig.kt` (Modify)
* **Type / Scope**: Spring Security Filter Chain
* **Proposed Changes**: Add `.requestMatchers("/actuator/health", "/actuator/health/**").permitAll()` so container
  loopback probes succeed (`TrustedProxyFilter` already trusts `127.0.0.1/32` per `application.yaml:109`; Caddy blocks
  `/actuator*` externally with `404`).

#### 6. `KMP-ChatBackend/app/src/main/kotlin/io/github/neronguyenvn/nerochat/infra/caching/RedisConfig.kt` (Modify)
* **Type / Scope**: Cache Configuration
* **Proposed Changes**: Set `maxSize = 200` on `CacheNames.MESSAGES`'s `CacheConfig` so cached message slices cannot
  evict active OTP keys inside the 24 MB local Redis container.

#### 7. `KMP-ChatBackend/chat/src/main/kotlin/io/github/neronguyenvn/nerochat/chat/api/websocket/ChatWebSocketHandler.kt` (Modify)
* **Type / Scope**: WebSocket Handler (`:chat`)
* **Proposed Changes**:
  * Increase `PONG_TIMEOUT_MS` from `30_000L` to `70_000L` (> 2x `PING_INTERVAL_MS = 30_000L`).
  * Change `pingClients()` visibility from `private` to `internal` and inject
    `private val clockMillis: () -> Long = System::currentTimeMillis` (replacing direct `System.currentTimeMillis()`
    calls) for deterministic unit testing.

#### 8. `KMP-ChatBackend/app/src/test/kotlin/io/github/neronguyenvn/nerochat/api/security/ProdProfileHardeningTest.kt` (Create) & `ChatWebSocketHandlerTest.kt` (Modify)

* **Type / Scope**: Backend Automated Verification Tests
* **Proposed Changes**:
  * Create `ProdProfileHardeningTest` using
    `@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)` + `@ActiveProfiles("prod")` with H2
    datasource, `JPA_DDL_AUTO=create-drop`, and mocked `RedissonClient`/`RabbitTemplate`/`JavaMailSender`, sending
    `X-Real-IP: 1.2.3.4` to assert:
    * `GET /actuator/health/liveness` returns `200` with `{"status":"UP"}`.
    * `GET /v3/api-docs` and `/swagger-ui/index.html` return `404` (SpringDoc disabled).
    * Resolved `HikariDataSource.minimumIdle == 0` and `maximumPoolSize == 4`.
  * Add a test in `ChatWebSocketHandlerTest` advancing `clockMillis` to 35s (asserting session stays open on
    `pingClients()`) and 71s (asserting session closes with `SESSION_NOT_RELIABLE`).

* **Phase 1 Checkpoint**: `./scripts/sync-archify.sh --check && ./scripts/verify.sh clean` (including
  `ProdProfileHardeningTest`, `ChatWebSocketHandlerTest`, and `OpenApiExportTest`).

---

### Phase 2 — Backend Container, Caddy, CI/Deploy Workflows & Docs (`KMP-ChatBackend`)

#### 9. `KMP-ChatBackend/Caddyfile` (Modify)

* **Type / Scope**: Edge Reverse Proxy Config (Caddyfile v2)
* **Proposed Changes**:
  * Configure `{$DOMAIN}` site block with structured stdout logging (`log { output stdout }`),
    `respond /v3/api-docs* 404`, `respond /swagger-ui* 404`, `respond /actuator* 404`, and
    `reverse_proxy backend:8080 { header_up X-Real-IP {remote_host} }`.
  * Add an explicit fallback HTTP block for unknown hosts/bare IP
    (`http:// { @not_domain not host {$DOMAIN}; handle @not_domain { abort } }`) so ACME HTTP-01 challenges for
    `{$DOMAIN}` continue working while unmatched scanner traffic does not consume GCP free-tier egress or incur
    excluded-region egress charges.

#### 10. `KMP-ChatBackend/docker-compose.prod.yml` (Modify)
* **Type / Scope**: Production Container Compose Topology
* **Proposed Changes**:
  * **Remove legacy local-stack blocks**: delete `postgres` and `rabbitmq` services, remove them from
    `backend.depends_on` (keeping only `redis: { condition: service_healthy }`), delete top-level `secrets:`, delete
    `redis_data:/data` mount, delete `postgres_data`/`redis_data`/`rabbitmq_data` volumes, and remove local RabbitMQ
    defaults (`5672`/`false`/`/`) so CloudAMQP values (`5671`/`true`/pass-through `RABBITMQ_VHOST`) apply from `.env`.
  * **`caddy` service**: `caddy:2-alpine`, `mem_limit: 96m`, `json-file` logs (`max-size: "10m"`, `max-file: "3"`),
    named volumes `caddy_data` and `caddy_config`.
  * **`redis` service**: `redis:7-alpine`, `mem_limit: 48m`, no host port, command
    `redis-server --requirepass ${REDIS_PASSWORD} --maxmemory 24mb --maxmemory-policy volatile-lru --save "" --appendonly no`,
    `json-file` logs (`10m x 3`).
  * **`backend` service**:
    * `image: ghcr.io/${GHCR_OWNER:-neronguyenvn}/kmp-chatbackend:${IMAGE_TAG:-main}` (replacing `build:`).
    * `mem_limit: 550m`, `memswap_limit: 750m`, `json-file` logs (`10m x 3`).
    *
    `JAVA_TOOL_OPTIONS="-XX:+UseSerialGC -Xms96m -Xmx224m -XX:MaxMetaspaceSize=128m -XX:MaxDirectMemorySize=32m -XX:ReservedCodeCacheSize=32m -Xss256k -XX:+ExitOnOutOfMemoryError"`.
    * `env_file: [.env, .deploy-state/current.env]` (with optional `required: false` on `current.env` or ensured by
      deploy script) and `environment:` passing `POSTGRES_URL` from `.env`, `REDIS_POOL_SIZE: 4`,
      `REDIS_SUB_POOL_SIZE: 2`, `REDIS_THREADS: 2`, `REDIS_NETTY_THREADS: 4`.
    * `healthcheck:` running BusyBox `wget -q --spider http://127.0.0.1:8080/actuator/health/liveness` (`interval: 30s`,
      `timeout: 5s`, `retries: 3`, `start_period: 90s`).
  * **Network**: Pin `nerochat_internal` IPAM subnet to `172.28.0.0/24` (inside `TrustedProxyFilter`'s `172.16.0.0/12`).

#### 11. `KMP-ChatBackend/.env.example` (Modify)
* **Type / Scope**: Environment Contract Template
* **Proposed Changes**: Document all required variables for GCP + Neon (`-pooler` +
  `?sslmode=require&prepareThreshold=0&preparedStatementCacheQueries=0`, plus direct-URL bootstrap note), local Redis
  (`REDIS_HOST=redis`, `REDIS_POOL_SIZE=4`, `REDIS_SUB_POOL_SIZE=2`), CloudAMQP (`RABBITMQ_PORT=5671`,
  `RABBITMQ_SSL_ENABLED=true`, 8-char `RABBITMQ_VHOST`), Brevo (`SMTP_USERNAME` vs verified `SMTP_SENDER`), `GHCR_OWNER`
  (lowercase), and remove host `GITHUB_TOKEN` PAT.

#### 12. `KMP-ChatBackend/scripts/deploy/nerochat-deploy.sh` (Create)

* **Type / Scope**: Version-Controlled Forced-Command VM Deploy Script (installed to `/usr/local/bin/nerochat-deploy`)
* **Proposed Changes**:
  * Enforce `set -euo pipefail`, forbid `set -x` and `docker compose config` stdout output (preventing `.env` leakage to
    public CI logs), operate in `/opt/nerochat`, and source `/opt/nerochat/.env`.
  * Validate `$SSH_ORIGINAL_COMMAND` strictly against `^sha-[0-9a-f]{7,40}$`.
  * Pre-pull `ghcr.io/${GHCR_OWNER}/kmp-chatbackend:${TARGET_TAG}` from public GHCR (with stop-before-pull fallback if
    host RAM is constrained).
  * Record existing `IMAGE_TAG` from `.deploy-state/current.env` into `.deploy-state/prev_tag` (handling first-boot
    absence).
  * Write `IMAGE_TAG=${TARGET_TAG}` to `.deploy-state/current.env` and run
    `docker compose -f docker-compose.prod.yml up -d --no-deps backend`.
  * Execute 5 local rollback-gating checks (capturing non-zero `wget`/`curl` exit codes without `set -e` bypassing
    rollback):
    1. Poll `docker exec nerochat-backend wget -qO- http://127.0.0.1:8080/actuator/health/liveness` -> `UP` (90s
       budget).
    2. One-shot `docker exec nerochat-backend wget -qO- http://127.0.0.1:8080/actuator/health/readiness` -> `UP`.
    3. `curl -sS --resolve "${DOMAIN}:443:127.0.0.1" "https://${DOMAIN}/api/chat/rooms"` -> `401` +
       `CommonServerError.Unauthorized`.
    4. `curl -sS --resolve "${DOMAIN}:443:127.0.0.1"` on `/v3/api-docs`, `/swagger-ui/index.html`, and
       `/actuator/health/liveness` -> `404`.
    5. `docker inspect -f '{{.RestartCount}}' nerochat-backend` == `0`.
  * On success: prune unused backend images while retaining `TARGET_TAG` and `prev_tag`.
  * On failure: if `prev_tag` exists (skipping rollback on first-ever boot), restore `IMAGE_TAG=${prev_tag}` in
    `.deploy-state/current.env`, restart `backend`, re-verify `/liveness`, and exit non-zero.

#### 13. `KMP-ChatBackend/.github/workflows/ci.yml` (Modify) & `.github/workflows/deploy.yml` (Create)

* **Type / Scope**: Backend CI, Public GHCR Publish & Manual Deploy Workflows
* **Proposed Changes**:
  * `ci.yml`: Keep `test` job (`sync-archify.sh --check` + `./scripts/verify.sh clean`); add `publish` job
    (`if: github.event_name == 'push' && github.ref == 'refs/heads/main'`, `needs: test`,
    `permissions: { contents: read, packages: write }`) building `linux/amd64` with BuildKit secrets (`GITHUB_ACTOR`,
    `GITHUB_TOKEN` for `KMP-ChatContract`) and pushing `ghcr.io/<lowercase-owner>/kmp-chatbackend:sha-<short>` and
    `:main`.
  * `deploy.yml`: `workflow_dispatch`-only workflow with input `image_tag` (regex-validated `^sha-[0-9a-f]{7,40}$`),
    `concurrency: { group: prod-deploy, cancel-in-progress: false }`, GitHub Environment `production`, secrets
    `DEPLOY_HOST`, `DEPLOY_USER`, `DEPLOY_SSH_KEY`, `DEPLOY_SSH_KNOWN_HOSTS`, and var `PROD_DOMAIN`. Step 1 invokes the
    forced-command SSH key with `image_tag`; Step 2 runs external public-DNS `curl` checks against
    `https://${PROD_DOMAIN}/api/chat/rooms` (`401`) and `/v3/api-docs` (`404`).

#### 14. `KMP-ChatBackend/docs/DEPLOYMENT.md` (Modify), `docs/README.md` (Modify), `app/README.md` (Modify), `chat/README.md` (Modify)

* **Type / Scope**: Operational Runbook, Registry & Module Contracts
* **Proposed Changes**:
  * Rewrite `docs/DEPLOYMENT.md` as the GCP `e2-micro` + Managed Services runbook: VM swap/firewall/forced-command SSH
    setup, `/opt/nerochat/` initial clone and update procedure, flipping GHCR package visibility to Public, exact
    one-time Neon direct-URL bootstrap (`docker compose -f docker-compose.prod.yml up -d backend` with direct URL +
    `JPA_DDL_AUTO=update`/`JPA_CREATE_NAMESPACES=true`, then flipping `.env` to `-pooler` + `validate`/`false`),
    disposable DB reset (`DROP SCHEMA user_service, chat_service CASCADE;`), DuckDNS + Brevo setup, and production
    triage commands.
  * Register `docs/DEPLOYMENT.md` in `KMP-ChatBackend/docs/README.md`
    (`docs/designs/gcp-managed-free-tier-deployment.md` is already created and registered; flip its status from `Draft`
    to `Implemented` only after Phase 5 passes).
  * Update `app/README.md` (single-port Actuator probes, `prod` profile invariants, Redis `maxSize = 200`) and
    `chat/README.md` (`70_000L` pong timeout).

* **Phase 2 Checkpoint**:
  `docker compose --env-file .env.example -f docker-compose.prod.yml config && ./scripts/sync-archify.sh --check && ./scripts/verify.sh clean`.

---

### Phase 3 — Android Release Signing, R8 Keep Rules, Network & Backup Hardening (`Android-ChatApp`)

#### 15. `Android-ChatApp/app/build.gradle.kts` (Modify)

* **Type / Scope**: Android Application Gradle Build (Configuration-Cache safe)
* **Proposed Changes**:
  * Replace hardcoded `signingConfigs.getByName("debug")` in `buildTypes.release` with a `release` signing config
    reading `RELEASE_KEYSTORE_PATH`, `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS`, and `RELEASE_KEY_PASSWORD` via
    `providers.gradleProperty` / `providers.environmentVariable`, failing fast during release assemble/bundle tasks if
    any credential is missing.
  * Wire `proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")`.
  * Parameterize `versionCode` (`providers.gradleProperty("VERSION_CODE")`, default `1`) and `versionName`
    (`providers.gradleProperty("VERSION_NAME")`, default `"0.1.0-local"`).

#### 16. `Android-ChatApp/app/proguard-rules.pro` (Create)

* **Type / Scope**: R8 Full-Mode Keep Rules
* **Proposed Changes**: Add explicit R8 directives:
  * `-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod`
  * Keep `kotlinx.serialization` generated companions and serializers for `io.github.neronguyenvn.nerochat.contract.**`
    (`-keep,includedescriptorclasses class io.github.neronguyenvn.nerochat.contract.** { *; }`,
    `-keepclassmembers class io.github.neronguyenvn.nerochat.contract.** { *** Companion; *** serializer(...); }`,
    covering `WsClientMessage`, `WsServerEvent`, `ServerError`, and all DTOs).
  * Keep private Retrofit service interfaces and suspend signatures in
    `io.github.neronguyen.chat.core.network.retrofit.**`
    (`-keep,allowobfuscation,allowshrinking interface io.github.neronguyen.chat.core.network.retrofit.** { *; }`).
  * Keep Navigation 3 `@Serializable` route keys (`io.github.neronguyen.chat.navigation.**`,
    `io.github.neronguyen.chat.feature.**.navigation.**`) and DataStore serializable model
    (`io.github.neronguyen.chat.core.datastore.**`).
  * Keep Hilt WorkManager workers (`io.github.neronguyen.chat.sync.work.**`).

#### 17. `Android-ChatApp/core/network/build.gradle.kts` (Modify)

* **Type / Scope**: Network Library Gradle Build (Configuration-Cache safe)
* **Proposed Changes**: Normalize resolved `BASE_URL` (from `providers.gradleProperty("BASE_URL")` / `local.properties`)
  to always end with `/`, and register a release-only validation check via
  `androidComponents.onVariants(selector().withBuildType("release"))` rejecting blank values, non-`https://` schemes,
  `localhost`, `127.0.0.1`, and `10.0.2.2`.

#### 18. `Android-ChatApp/core/network/src/main/kotlin/io/github/neronguyen/chat/core/network/di/NetworkModule.kt` (Modify) & `ChatWebSocketClient.kt` (Modify)

* **Type / Scope**: OkHttp & WebSocket Client (`:core:network`)
* **Proposed Changes**:
  * In `NetworkModule.kt`: increase `@BaseOkHttp` `.callTimeout(15.seconds)` to `.callTimeout(35.seconds)`
    (`@RealtimeOkHttp` preserves `.callTimeout(0, TimeUnit.SECONDS)`).
  * In `ChatWebSocketClient.kt` (lines 124–126): log non-fatal `WsServerEvent` JSON decode exceptions via
    `android.util.Log.w` instead of silently ignoring them (without closing the socket).

#### 19. `Android-ChatApp/app/src/main/AndroidManifest.xml` (Modify), `app/src/main/res/xml/data_extraction_rules.xml` (Delete), `app/src/main/res/xml/backup_rules.xml` (Delete)

* **Type / Scope**: Android Backup & Transfer Policy
* **Proposed Changes**: Set `android:allowBackup="false"`, remove `android:dataExtractionRules` and
  `android:fullBackupContent` from `AndroidManifest.xml`, and delete the two unused boilerplate XML files so Room SQLite
  and DataStore are excluded across all Android versions including API 31+ (`targetSdk = 37`).

#### 20. `Android-ChatApp/.github/workflows/ci.yml` (Modify)

* **Type / Scope**: Android PR CI Workflow
* **Proposed Changes**: Add a step that generates an ephemeral throwaway PKCS12 keystore via `keytool -genkeypair`, runs
  `./gradlew :app:assembleRelease -PBASE_URL=https://example.invalid/ -PRELEASE_KEYSTORE_PATH=/tmp/ci-ephemeral.p12 -PRELEASE_KEYSTORE_PASSWORD=ci-test-pass -PRELEASE_KEY_ALIAS=ci -PRELEASE_KEY_PASSWORD=ci-test-pass --no-daemon`,
  and runs a post-build grep check asserting `WsClientMessage`, `WsServerEvent`, and `ServerError` `$$serializer`
  entries exist in `app/build/outputs/mapping/release/mapping.txt` (or `seeds.txt`).

#### 21. `Android-ChatApp/app/README.md` (Modify), `core/network/README.md` (Modify), `docs/README.md` (Modify)
* **Type / Scope**: Module Contracts & Registry
* **Proposed Changes**: Document release signing/ProGuard/backup exclusion in `app/README.md`, document `BASE_URL`
  normalization/HTTPS release guard and 35s `callTimeout` in `core/network/README.md`, and add a cross-repo reference
  row in `Android-ChatApp/docs/README.md` pointing to
  `KMP-ChatBackend/docs/designs/gcp-managed-free-tier-deployment.md`.

* **Phase 3 Checkpoint**: `./scripts/sync-archify.sh --check && ./scripts/verify.sh clean` plus local ephemeral-keystore
  `:app:assembleRelease` and `$$serializer` symbol check.

---

### Phase 4 — Android Signed GitHub Releases Workflow (`Android-ChatApp`)

#### 22. `Android-ChatApp/.github/workflows/release.yml` (Create)

* **Type / Scope**: Signed Release APK Pipeline
* **Proposed Changes**: Trigger on `v*` tag push (`vX.Y.Z`) or manual `workflow_dispatch` (input `version_name`). Run
  `./scripts/verify.sh clean`, decode `secrets.ANDROID_KEYSTORE_BASE64`, build signed `:app:assembleRelease` with
  `vars.PROD_BASE_URL`, `VERSION_CODE=${{ github.run_number }}`, `VERSION_NAME` (tag without `v` or dispatch input), and
  secrets `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`. Generate `.sha256` checksum, upload
  `app/build/outputs/mapping/release/mapping.txt` as a workflow artifact, and create a draft GitHub Release
  (`nerochat-v<version>-release.apk` + `.sha256`) with tester sideloading and Brevo Spam-check notes.

* **Phase 4 Checkpoint**: Validate workflow YAML syntax and run a manual `workflow_dispatch` dry run generating a draft
  GitHub Release with archived `mapping.txt`.

---

### Phase 5 — Live Provisioning, Rollback Drill & Signed APK Smoke Verification

* **Host & Managed Services Pre-Flight (`nerochat-prod`)**:
  * Confirm `pd-standard` 30 GB disk, `PREMIUM` network tier, `$1.00` billing budget alert, 2 GB swapfile +
    `vm.swappiness=10`, Docker + Compose installed, `/opt/nerochat/` populated, `/usr/local/bin/nerochat-deploy`
    installed, and CI forced-command SSH key configured in `~/.ssh/authorized_keys`.
  * Set `ghcr.io/<lowercase-owner>/kmp-chatbackend` visibility to **Public** after the first `ci.yml` push to `main`.
  * Run one-time Neon direct-URL bootstrap (`JPA_DDL_AUTO=update`, `JPA_CREATE_NAMESPACES=true`), then flip `.env`
    permanently to `-pooler` + `JPA_DDL_AUTO=validate` + `JPA_CREATE_NAMESPACES=false`.
* **Live Deploy, Memory, Idle-Neon & Rollback Drills**:
  * Trigger `deploy.yml` with a healthy `sha-<short>` tag -> assert all 5 local checks + public-DNS HTTPS checks pass,
    and confirm `docker stats --no-stream` + `free -m` stay within the `694m` container limit budget with zero sustained
    swap I/O (including during `docker pull`).
  * **Rollback drill**: Trigger `deploy.yml` with an intentionally failing image tag -> assert
    `/usr/local/bin/nerochat-deploy` restarts `prev_tag`, restores `/liveness`, and fails the GitHub Actions job.
  * **Idle Neon check**: Leave backend idle for 15 minutes while Docker `/liveness` runs -> confirm Neon dashboard shows
    compute suspended to `0 CU`, then send a login request and confirm cold wake succeeds within client timeout.
* **Live Signed R8 APK Smoke Test (Before Publishing Draft Release)**:
  * Install the signed R8 APK from `release.yml` on a real Android device pointing to `https://<name>.duckdns.org/`.
  * Complete CUJ 1: register -> confirm Brevo OTP delivery (inbox/Spam/Brevo logs) -> verify OTP -> login -> search
    peer -> exchange `wss://` messages for > 2 minutes without 30s disconnects -> force-close and reopen (verify
    Navigation 3 + Room restoration).
  * Complete CUJ 4: install a second signed release APK with incremented `versionCode` over the first and confirm
    in-place upgrade succeeds with local chat history preserved.

--------------------------------------------------------------------------------

*Drafted with EGM*
