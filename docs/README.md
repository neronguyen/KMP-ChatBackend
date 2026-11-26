# KMP-ChatBackend — Architecture & Design Registry

This is the unified documentation index for `KMP-ChatBackend`, linking C4 component architecture, Archify
sequence/lifecycle/dataflow specifications, API specifications, and Elephant-and-Goldfish Model (EGM) technical design
documents.

> **Governance & Authoring Rules**: Follow global git rules in `~/.gemini/config/AGENTS.md`, ecosystem lifecycle
> governance in [`../KMP-ChatContract/docs/GOVERNANCE.md`](../../KMP-ChatContract/docs/GOVERNANCE.md), architectural
> philosophy in [`../KMP-ChatContract/docs/PHILOSOPHY.md`](../../KMP-ChatContract/docs/PHILOSOPHY.md), and repository
> boundary invariants in [`../AGENTS.md`](../AGENTS.md).

---

## 1. C4 Component Architecture (Level 3) & API Specs

| Artifact                      | Type / Level | Status    | Specification (JSON)                                                                               | Interactive Viewer (HTML)                                                             | Static Assets (Dark / Light)                                                                                     |
|-------------------------------|--------------|-----------|----------------------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------|
| **Backend Server Components** | C4 Level 3   | `[AS-IS]` | [`backend-components.archify.json`](./architecture/specifications/backend-components.archify.json) | [`c4-backend-components.html`](./architecture/interactive/c4-backend-components.html) | [Dark](./architecture/assets/BackendComponents.png) · [Light](./architecture/assets/BackendComponents.light.png) |
| **OpenAPI 3.1.0 Specification** | REST Schema  | `[AS-IS]` | [`openapi.json`](./openapi.json)                                                                   | —                                                                                     | —                                                                                                                |
| **Postman Collection**        | API Client   | `[AS-IS]` | [`nerochat.postman_collection.json`](./nerochat.postman_collection.json)                           | —                                                                                     | —                                                                                                                |

---

## 2. Architecture Flows & Lifecycles

Shared client–server wire flows (`ws-lifecycle`, `token-refresh`, `optimistic-send`) are maintained in the Contract SSOT
registry: [`KMP-ChatContract/docs/README.md`](../../KMP-ChatContract/docs/README.md).

| Flow                                  | Type | Status | Specification & Artifacts | Linked Design Spec | Asserting Test Suite |
|---------------------------------------|------|--------|---------------------------|--------------------|----------------------|
| *(No backend-only flow diagrams yet)* | —    | —      | —                         | —                  | —                    |

---

## 3. Technical Design Documents (EGM)

| Design Document                                                                                                                  | Date       | Status        | Target Modules                                   | Linked Archify Specs                                                                                                       | Summary                                                                                                                                            |
|----------------------------------------------------------------------------------------------------------------------------------|------------|---------------|--------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------|
| [**6-Digit OTP Email Verification & Password Reset**](../../KMP-ChatContract/docs/designs/otp-email-verification.md)             | 2026-11-12 | `Implemented` | `:user`, `:notification`                         | [Contract Flow Spec](../../KMP-ChatContract/docs/architecture/specifications/flows/otp-verification.sequence.archify.json) | Cross-repo migration from email deep links to 6-digit OTP backed purely by Redisson.                                                               |
| [**Local E2E Harness & Service CLI (A4)**](../../Android-ChatApp/docs/designs/local-e2e-harness.md)                              | 2026-11-15 | `Implemented` | `:app` (infra & `e2e` profile)                   | —                                                                                                                          | Docker Compose (`postgres`, `redis`, `rabbitmq`, `mailpit`) and `application-e2e.yaml` backend setup for cross-repo Tier 3 E2E verification.       |
| [**$0 Hybrid Free-Tier Production Deployment & Live Release Pipeline (CHAT-26)**](./designs/gcp-managed-free-tier-deployment.md) | 2026-11-26 | `Draft`       | `:app`, `:chat`, infra, CI/CD, `Android-ChatApp` | —                                                                                                                          | GCP Always Free `e2-micro` + Caddy + 24 MB local Redis + Neon/CloudAMQP/Brevo + GHCR airlocked deploy + signed R8 Android GitHub Release pipeline. |
