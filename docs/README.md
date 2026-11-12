# KMP-ChatBackend — Architecture & Design Registry

This is the unified documentation index for `KMP-ChatBackend`, linking C4 component architecture, Archify
sequence/lifecycle/dataflow specifications, API specifications, and Elephant-and-Goldfish Model (EGM) technical design
documents.

> **Governance & Authoring Rules**: Follow global agent lifecycle rules in `~/.gemini/config/AGENTS.md`, architectural
> philosophy in [`../KMP-ChatContract/docs/PHILOSOPHY.md`](../../KMP-ChatContract/docs/PHILOSOPHY.md), and repository
> boundary invariants in [`../AGENTS.md`](../AGENTS.md).

---

## 1. C4 Component Architecture (Level 3) & API Specs

| Artifact                      | Type / Level | Status    | Specification (JSON)                                                                               | Interactive Viewer (HTML)                                                             | Static Assets (Dark / Light)                                                                                     |
|-------------------------------|--------------|-----------|----------------------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------|
| **Backend Server Components** | C4 Level 3   | `[AS-IS]` | [`backend-components.archify.json`](./architecture/specifications/backend-components.archify.json) | [`c4-backend-components.html`](./architecture/interactive/c4-backend-components.html) | [Dark](./architecture/assets/BackendComponents.png) · [Light](./architecture/assets/BackendComponents.light.png) |
| **OpenAPI 3.0 Specification** | REST Schema  | `[AS-IS]` | [`openapi.json`](./openapi.json)                                                                   | —                                                                                     | —                                                                                                                |
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

| Design Document                   | Date | Status | Target Modules | Linked Archify Specs | Summary                       |
|-----------------------------------|------|--------|----------------|----------------------|-------------------------------|
| *(No active feature designs yet)* | —    | —      | —              | —                    | Unified registry initialized. |
