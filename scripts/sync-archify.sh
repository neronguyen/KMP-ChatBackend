#!/usr/bin/env bash
set -euo pipefail

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ACTION="${1:-changed}"

# Check required binaries
CHROME_BIN=""
if command -v flatpak >/dev/null 2>&1 && flatpak info com.google.Chrome >/dev/null 2>&1; then
  CHROME_BIN="flatpak run --filesystem=host com.google.Chrome"
elif [ -x "${HOME}/.local/bin/google-chrome" ]; then
  CHROME_BIN="${HOME}/.local/bin/google-chrome"
elif command -v google-chrome >/dev/null 2>&1; then
  CHROME_BIN="google-chrome"
elif command -v chromium >/dev/null 2>&1; then
  CHROME_BIN="chromium"
fi

ARCHIFY_CLI="${HOME}/.gemini/config/skills/archify/bin/archify.mjs"
if [ ! -f "$ARCHIFY_CLI" ]; then
  ARCHIFY_CLI="${HOME}/.agents/skills/archify/bin/archify.mjs"
fi

get_spec_type() {
  local json_file="$1"
  python3 -c "import json; data=json.load(open('$json_file')); print(data.get('diagram_type', 'architecture'))"
}

get_spec_output_html() {
  local json_file="$1"
  python3 -c "import json; data=json.load(open('$json_file')); print(data.get('meta', {}).get('output', ''))"
}

get_asset_names() {
  local json_file="$1"
  local base
  base="$(basename "$json_file")"
  base="${base%%.*}"
  case "$base" in
    android-components)
      echo "AndroidComponents.png AndroidComponents.light.png"
      ;;
    backend-components)
      echo "BackendComponents.png BackendComponents.light.png"
      ;;
    system-context)
      echo "SystemContext.png SystemContext.light.png"
      ;;
    containers)
      echo "Containers.png Containers.light.png"
      ;;
    *)
      echo "${base}.png ${base}.light.png"
      ;;
  esac
}

# Returns 0 if target is older than spec by more than 1 second (stale)
is_stale() {
  local target="$1"
  local spec="$2"
  python3 -c "
import os, sys
t_target = os.path.getmtime('$target')
t_spec = os.path.getmtime('$spec')
# Stale if target is older than spec by more than 1.0 second
sys.exit(0 if (t_spec - t_target) > 1.0 else 1)
"
}

render_spec() {
  local spec="$1"
  local spec_type
  spec_type="$(get_spec_type "$spec")"
  local rel_html
  rel_html="$(get_spec_output_html "$spec")"

  if [ -z "$rel_html" ]; then
    echo "[-] Warning: $spec has no meta.output specified, skipping."
    return 0
  fi

  local abs_html="${REPO_DIR}/${rel_html}"
  mkdir -p "$(dirname "$abs_html")"

  echo "==> [Archify] Delivering $spec_type: $spec -> $rel_html"
  PATH="${HOME}/.local/bin:${PATH}" node "$ARCHIFY_CLI" deliver "$spec_type" "$spec" "$abs_html" --repo-root "$REPO_DIR" --quality showcase --json >/dev/null

  echo "==> [Archify] Checking layout: $rel_html"
  PATH="${HOME}/.local/bin:${PATH}" node "$ARCHIFY_CLI" check "$abs_html" --json >/dev/null

  # Export static images
  if [ -n "$CHROME_BIN" ]; then
    local assets
    assets="$(get_asset_names "$spec")"
    read -r dark_png light_png <<< "$assets"

    local abs_dark="${REPO_DIR}/docs/architecture/assets/${dark_png}"
    local abs_light="${REPO_DIR}/docs/architecture/assets/${light_png}"
    mkdir -p "$(dirname "$abs_dark")"

    echo "==> [Chrome] Exporting PNGs for $rel_html..."
    $CHROME_BIN --headless=new --disable-gpu \
      --screenshot="$abs_dark" \
      --window-size=1200,900 "file://${abs_html}?theme=dark" >/dev/null 2>&1
    $CHROME_BIN --headless=new --disable-gpu \
      --screenshot="$abs_light" \
      --window-size=1200,900 "file://${abs_html}?theme=light" >/dev/null 2>&1
  else
    echo "[-] Warning: Headless Chrome not found, skipping PNG asset export."
  fi

  # Clean delivery sidecar if generated
  rm -f "$(dirname "$abs_html")"/*.delivery.json
  echo "[OK] Successfully synced: $(basename "$spec")"
}

check_all() {
  echo "==> [Diagram Gate] Checking diagram freshness across repository..."
  local stale_count=0
  local specs
  mapfile -t specs < <(find "${REPO_DIR}/docs/architecture/specifications" -name "*.archify.json" 2>/dev/null || true)

  # Collect changed files in working tree or PR/commit diff
  local changed_lookup=" "
  while IFS= read -r f; do
    [ -n "$f" ] && changed_lookup+="$f "
  done < <(git -C "$REPO_DIR" status --porcelain 2>/dev/null | awk '{print $2}' || true)

  if git -C "$REPO_DIR" rev-parse --verify origin/main >/dev/null 2>&1; then
    while IFS= read -r f; do
      [ -n "$f" ] && changed_lookup+="$f "
    done < <(git -C "$REPO_DIR" diff --name-only origin/main...HEAD 2>/dev/null || true)
  elif git -C "$REPO_DIR" rev-parse --verify HEAD~1 >/dev/null 2>&1; then
    while IFS= read -r f; do
      [ -n "$f" ] && changed_lookup+="$f "
    done < <(git -C "$REPO_DIR" diff --name-only HEAD~1...HEAD 2>/dev/null || true)
  fi

  for spec in "${specs[@]}"; do
    local rel_spec="${spec#$REPO_DIR/}"
    local spec_in_diff=false
    if [[ "$changed_lookup" =~ " $rel_spec " ]]; then
      spec_in_diff=true
    fi

    local rel_html
    rel_html="$(get_spec_output_html "$spec")"
    if [ -z "$rel_html" ]; then continue; fi

    local abs_html="${REPO_DIR}/${rel_html}"
    if [ ! -f "$abs_html" ]; then
      echo "  [ERROR] Missing HTML for $(basename "$spec"): $rel_html"
      ((stale_count++))
      continue
    fi

    # 1. Local mtime freshness
    if is_stale "$abs_html" "$spec"; then
      echo "  [STALE] HTML is older than spec: $(basename "$spec")"
      ((stale_count++))
    fi

    # 2. Git diff parity: if spec is modified, HTML must also be updated
    if [ "$spec_in_diff" = true ] && [[ ! "$changed_lookup" =~ " $rel_html " ]]; then
      echo "  [STALE] Spec modified in diff but HTML not updated: $rel_html"
      ((stale_count++))
    fi

    local assets
    assets="$(get_asset_names "$spec")"
    read -r dark_png light_png <<< "$assets"
    for img in "$dark_png" "$light_png"; do
      local rel_img="docs/architecture/assets/${img}"
      local abs_img="${REPO_DIR}/${rel_img}"
      if [ ! -f "$abs_img" ]; then
        echo "  [ERROR] Missing asset: ${rel_img}"
        ((stale_count++))
      elif is_stale "$abs_img" "$spec"; then
        echo "  [STALE] Asset is older than spec: ${rel_img}"
        ((stale_count++))
      fi

      # Git diff parity: if spec is modified, static assets must also be updated
      if [ "$spec_in_diff" = true ] && [[ ! "$changed_lookup" =~ " $rel_img " ]]; then
        echo "  [STALE] Spec modified in diff but asset not updated: ${rel_img}"
        ((stale_count++))
      fi
    done
  done

  if [ "$stale_count" -gt 0 ]; then
    echo "[-] Architecture diagrams check FAILED: $stale_count stale or missing file(s)."
    echo "[-] Run './scripts/sync-archify.sh --all' to re-render all diagrams."
    return 1
  else
    echo "[OK] All architecture diagrams are up to date."
    return 0
  fi
}

case "$ACTION" in
  --check)
    check_all
    ;;
  --all)
    echo "==> [Sync] Re-rendering ALL architecture diagrams..."
    while IFS= read -r spec; do
      render_spec "$spec"
    done < <(find "${REPO_DIR}/docs/architecture/specifications" -name "*.archify.json")
    ;;
  changed|*)
    if [ "$ACTION" != "changed" ] && [ -f "${REPO_DIR}/docs/architecture/specifications/${ACTION}.archify.json" ]; then
      render_spec "${REPO_DIR}/docs/architecture/specifications/${ACTION}.archify.json"
    elif [ "$ACTION" != "changed" ] && [ -f "${REPO_DIR}/docs/architecture/specifications/flows/${ACTION}.sequence.archify.json" ]; then
      render_spec "${REPO_DIR}/docs/architecture/specifications/flows/${ACTION}.sequence.archify.json"
    elif [ "$ACTION" != "changed" ] && [ -f "${REPO_DIR}/docs/architecture/specifications/flows/${ACTION}.lifecycle.archify.json" ]; then
      render_spec "${REPO_DIR}/docs/architecture/specifications/flows/${ACTION}.lifecycle.archify.json"
    elif [ "$ACTION" != "changed" ] && [ -f "$ACTION" ]; then
      render_spec "$ACTION"
    else
      # Detect changed archify.json files via git
      CHANGED_SPECS=()
      while IFS= read -r line; do
        if [[ "$line" =~ \.archify\.json$ ]] && [ -f "${REPO_DIR}/${line}" ]; then
          CHANGED_SPECS+=("$line")
        fi
      done < <(git -C "$REPO_DIR" status --porcelain 2>/dev/null | awk '{print $2}' || true)

      # Check against main if working tree has no uncommitted archify specs
      if [ "${#CHANGED_SPECS[@]}" -eq 0 ]; then
        while IFS= read -r line; do
          if [[ "$line" =~ \.archify\.json$ ]] && [ -f "${REPO_DIR}/${line}" ]; then
            CHANGED_SPECS+=("$line")
          fi
        done < <(git -C "$REPO_DIR" diff --name-only main...HEAD 2>/dev/null || true)
      fi

      if [ "${#CHANGED_SPECS[@]}" -eq 0 ]; then
        echo "[i] No modified *.archify.json specifications detected."
      else
        echo "==> [Sync] Found ${#CHANGED_SPECS[@]} modified specification(s):"
        for s in "${CHANGED_SPECS[@]}"; do
          render_spec "${REPO_DIR}/${s}"
        done
      fi
    fi
    ;;
esac
