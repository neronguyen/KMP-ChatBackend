# syntax=docker/dockerfile:1

FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /workspace

COPY gradle gradle
COPY build-logic build-logic
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY app/build.gradle.kts app/build.gradle.kts
COPY common/build.gradle.kts common/build.gradle.kts
COPY chat/build.gradle.kts chat/build.gradle.kts
COPY user/build.gradle.kts user/build.gradle.kts
COPY notification/build.gradle.kts notification/build.gradle.kts

COPY app/src app/src
COPY common/src common/src
COPY chat/src chat/src
COPY user/src user/src
COPY notification/src notification/src

RUN --mount=type=secret,id=github_actor,required=false \
    --mount=type=secret,id=github_token,required=false \
    chmod +x gradlew && \
    sh -c '\
      if [ -f /run/secrets/github_actor ]; then export GITHUB_ACTOR=$(cat /run/secrets/github_actor); fi; \
      if [ -f /run/secrets/github_token ]; then export GITHUB_TOKEN=$(cat /run/secrets/github_token); fi; \
      ./gradlew :app:bootJar -PuseLocalContract=false --no-daemon \
    ' && \
    cp "$(ls app/build/libs/*.jar | grep -v '\-plain\.jar$' | head -n 1)" /workspace/app.jar

FROM eclipse-temurin:21-jre-alpine AS runtime
WORKDIR /app

RUN addgroup -S nerochat && adduser -S nerochat -G nerochat

COPY --from=builder /workspace/app.jar /app/app.jar
RUN chown nerochat:nerochat /app/app.jar

USER nerochat

ENV JAVA_TOOL_OPTIONS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=70.0 -XX:+ExitOnOutOfMemoryError"
ENV PORT=8080

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
