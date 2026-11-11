#!/usr/bin/env bash
set -euo pipefail

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# Resolve JDK 21 dynamically
RESOLVED_JDK=""
if [ -n "${JAVA_HOME:-}" ] && [ -x "${JAVA_HOME}/bin/java" ]; then
  if "${JAVA_HOME}/bin/java" -version 2>&1 | grep -q 'version "21'; then
    RESOLVED_JDK="$JAVA_HOME"
  fi
fi

if [ -z "$RESOLVED_JDK" ] && command -v java >/dev/null 2>&1 && java -version 2>&1 | grep -q 'version "21'; then
  JAVA_BIN="$(readlink -f "$(command -v java)")"
  RESOLVED_JDK="$(dirname "$(dirname "$JAVA_BIN")")"
fi

if [ -z "$RESOLVED_JDK" ] && [ -d "${HOME}/.gradle/jdks" ]; then
  CANDIDATE_JDK="$(find "${HOME}/.gradle/jdks" -maxdepth 1 -type d -name "*-21-*" 2>/dev/null | head -n 1 || true)"
  if [ -n "$CANDIDATE_JDK" ] && [ -x "$CANDIDATE_JDK/bin/java" ]; then
    RESOLVED_JDK="$CANDIDATE_JDK"
  fi
fi

if [ -n "$RESOLVED_JDK" ]; then
  export JAVA_HOME="$RESOLVED_JDK"
  export PATH="$JAVA_HOME/bin:$PATH"
fi

IS_CI="${CI:-false}"
GRADLE_ARGS=(-p "$REPO_DIR")

if [ "$IS_CI" = "true" ]; then
  GRADLE_ARGS+=(--no-daemon)
else
  export GRADLE_RO_DEP_CACHE="${GRADLE_RO_DEP_CACHE:-${HOME}/.gradle/caches}"
  GRADLE_USER_HOME="${GRADLE_USER_HOME:-/tmp/gradle-ro-home}"
  mkdir -p "$GRADLE_USER_HOME"
  if [ -d "${HOME}/.gradle/jdks" ] && [ ! -e "$GRADLE_USER_HOME/jdks" ]; then
    ln -sfn "${HOME}/.gradle/jdks" "$GRADLE_USER_HOME/jdks"
  fi
  if [ -d "${HOME}/.gradle/wrapper" ] && [ ! -e "$GRADLE_USER_HOME/wrapper" ]; then
    ln -sfn "${HOME}/.gradle/wrapper" "$GRADLE_USER_HOME/wrapper"
  fi
  GRADLE_ARGS+=(--gradle-user-home="$GRADLE_USER_HOME")
fi

# CI Mode: Full clean verification run
if [ "$IS_CI" = "true" ] || [ "${1:-}" = "clean" ]; then
  if [ "${1:-}" = "clean" ]; then shift; fi
  echo "==> [CLEAN GATE] Running clean check on KMP-ChatBackend (CI=${IS_CI})..."
  exec "$REPO_DIR/gradlew" "${GRADLE_ARGS[@]}" check --rerun-tasks "$@"
fi

# If specific gradle tasks/flags were passed, execute them directly
if [ "$#" -gt 0 ]; then
  echo "==> [DIRECT TASK] Running specified Gradle tasks: $*"
  exec "$REPO_DIR/gradlew" "${GRADLE_ARGS[@]}" "$@"
fi

# Local Fast Mode: Automatically detect affected modules via Git
echo "==> [AFFECTED MODULES] Detecting changed files via Git..."

CHANGED_FILES=()
while IFS= read -r f; do
  [ -n "$f" ] && CHANGED_FILES+=("$f")
done < <(git -C "$REPO_DIR" status --porcelain 2>/dev/null | awk '{print $2}' || true)

if git -C "$REPO_DIR" rev-parse --verify main >/dev/null 2>&1; then
  while IFS= read -r f; do
    [ -n "$f" ] && CHANGED_FILES+=("$f")
  done < <(git -C "$REPO_DIR" diff --name-only main...HEAD 2>/dev/null || true)
fi

UNIQUE_FILES=($(printf "%s\n" "${CHANGED_FILES[@]:-}" | sort -u))

RUN_ALL=false
GRADLE_TASKS=()

for file in "${UNIQUE_FILES[@]:-}"; do
  case "$file" in
    build.gradle.kts|settings.gradle.kts|gradle/*|build-logic/*)
      RUN_ALL=true
      break
      ;;
    app/*)
      GRADLE_TASKS+=(":app:check")
      ;;
    common/*)
      GRADLE_TASKS+=(":common:check")
      ;;
    user/*)
      GRADLE_TASKS+=(":user:check")
      ;;
    chat/*)
      GRADLE_TASKS+=(":chat:check")
      ;;
    notification/*)
      GRADLE_TASKS+=(":notification:check")
      ;;
  esac
done

if [ "$RUN_ALL" = "true" ] || [ "${#GRADLE_TASKS[@]}" -eq 0 ]; then
  if [ "$RUN_ALL" = "true" ]; then
    echo "==> [FAST FEEDBACK] Build config changed. Running check on entire project..."
  else
    echo "==> [FAST FEEDBACK] No specific module code changed. Running fast cached check..."
  fi
  exec "$REPO_DIR/gradlew" "${GRADLE_ARGS[@]}" check "$@"
fi

UNIQUE_TASKS=($(printf "%s\n" "${GRADLE_TASKS[@]}" | sort -u))
echo "==> [FAST FEEDBACK] Running ${#UNIQUE_TASKS[@]} affected task(s): ${UNIQUE_TASKS[*]}"
exec "$REPO_DIR/gradlew" "${GRADLE_ARGS[@]}" "${UNIQUE_TASKS[@]}" "$@"
