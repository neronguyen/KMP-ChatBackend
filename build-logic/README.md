# build-logic

## Overview
Gradle convention plugins standardizing build settings, Kotlin JVM toolchains, and common Spring Boot dependencies across all backend subprojects.

## Interface
- `[spring-boot-service.gradle.kts:spring-boot-service]`: Convention plugin applying `kotlin.jvm`, `kotlin.serialization`, `kotlin.spring`, `kotlin.jpa`, Java 21 toolchain, and baseline Spring Boot starters.

## Invariants
- Enforces JDK 21 toolchain on all applying subprojects.
- Standardizes compiler flags and serialization settings.

## Side Effects
- Configures Gradle build graph and test task defaults (`useJUnitPlatform()`).

## Verification
- Run: `./gradlew -p build-logic check`
