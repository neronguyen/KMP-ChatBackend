# app

## Overview
Spring Boot application bootstrap, root composition container, global security filter chain, Redisson configuration, and API documentation export tooling.

## Interface
- `[ChatApp.kt:ChatApp]`: Application entrypoint annotated with `@SpringBootApplication`.
- `[SecurityConfig.kt:SecurityConfig]`: Spring Security filter chain definition, endpoint authorization rules, stateless session management, and `contractJson()` bean (`ContractJson`).
- `[JwtAuthenticationFilter.kt:JwtAuthenticationFilter]`: Servlet filter authenticating Bearer JWT access tokens into Spring `SecurityContext`.
- `[TrustedProxyFilter.kt:TrustedProxyFilter]`: Reverse proxy header validator ensuring IP resolution integrity for rate limiters.
- `[RedisConfig.kt:RedisConfig]`: Redisson Spring cache manager bean configuration (`RedissonSpringCacheManager`).
- `[OpenApiConfig.kt:OpenApiConfig]`: SpringDoc OpenAPI 3.0 metadata and Bearer authentication scheme configuration.
- `[PostmanCollectionGenerator.kt:PostmanCollectionGenerator]`: Generator converting OpenAPI documentation into runnable Postman collection files.

## Invariants
- Composition root depending on `:common`, `:user`, `:chat`, `:notification`, and `:contract:{common,auth,chat}`.
- Stateless HTTP sessions: `SessionCreationPolicy.STATELESS`.
- CSRF disabled for stateless REST/WebSocket APIs.
- Reverse proxy headers trusted only when origin IP matches trusted subnet rules.

## Side Effects
- Binds server HTTP port (default 8080) and initializes database connection pool (HikariCP).
- Exports `docs/openapi.json` and `docs/nerochat.postman_collection.json`.

## Verification
- Run: `./gradlew :app:test --rerun-tasks`
