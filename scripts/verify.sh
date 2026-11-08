#!/usr/bin/env bash
set -euo pipefail

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MODE="${1:-fast}"

case "$MODE" in
  fast|clean)
    shift || true
    ;;
  *)
    echo "Usage: $0 [fast|clean] [gradle-args...]" >&2
    exit 1
    ;;
esac

# Resolve JDK 21 dynamically
if [ -z "${JAVA_HOME:-}" ]; then
  if command -v java >/dev/null 2>&1 && java -version 2>&1 | grep -q 'version "21'; then
    JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$(which java)")")")"
  else
    CANDIDATE_JDK="$(find "${HOME}/.gradle/jdks" -maxdepth 1 -type d -name "eclipse_adoptium-21-*" 2>/dev/null | head -n 1 || true)"
    if [ -n "$CANDIDATE_JDK" ] && [ -d "$CANDIDATE_JDK" ]; then
      export JAVA_HOME="$CANDIDATE_JDK"
      export PATH="$JAVA_HOME/bin:$PATH"
    fi
  fi
fi

export GRADLE_RO_DEP_CACHE="${GRADLE_RO_DEP_CACHE:-${HOME}/.gradle/caches}"
GRADLE_USER_HOME="${GRADLE_USER_HOME:-/tmp/gradle-ro-home}"

# Ensure isolated user home has necessary links for offline distribution & toolchain resolution
mkdir -p "$GRADLE_USER_HOME"
if [ -d "${HOME}/.gradle/jdks" ] && [ ! -e "$GRADLE_USER_HOME/jdks" ]; then
  ln -sfn "${HOME}/.gradle/jdks" "$GRADLE_USER_HOME/jdks"
fi
if [ -d "${HOME}/.gradle/wrapper" ] && [ ! -e "$GRADLE_USER_HOME/wrapper" ]; then
  ln -sfn "${HOME}/.gradle/wrapper" "$GRADLE_USER_HOME/wrapper"
fi

if [ "$MODE" = "clean" ]; then
  echo "==> [CLEAN GATE] Running full clean verification on KMP-ChatBackend..."
  "$REPO_DIR/gradlew" -p "$REPO_DIR" test --rerun-tasks --gradle-user-home="$GRADLE_USER_HOME" --offline "$@"
else
  echo "==> [FAST FEEDBACK] Running cached verification on KMP-ChatBackend..."
  "$REPO_DIR/gradlew" -p "$REPO_DIR" test --gradle-user-home="$GRADLE_USER_HOME" --offline "$@"
fi
