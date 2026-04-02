#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# ── Color helpers ────────────────────────────────────────────────────────────
CYAN='\033[0;36m'
YELLOW='\033[0;33m'
MAGENTA='\033[0;35m'
BOLD='\033[1m'
RESET='\033[0m'

# ── Process tracking ─────────────────────────────────────────────────────────
PIDS=()

cleanup() {
  printf "\n%bShutting down services…%b\n" "${BOLD}" "${RESET}"
  for pid in "${PIDS[@]:-}"; do
    if kill -0 "${pid}" 2>/dev/null; then
      kill "${pid}" 2>/dev/null || true
    fi
  done
  wait 2>/dev/null || true
  printf "All services stopped.\n"
}
trap cleanup EXIT INT TERM

# ── Log prefix helper ────────────────────────────────────────────────────────
run_service() {
  local color="$1"; shift
  local label="$1"; shift

  (
    "$@" 2>&1 | while IFS= read -r line; do
      printf "%b%-10s%b %s\n" "${color}" "${label}" "${RESET}" "${line}"
    done
  ) &
  PIDS+=($!)
}

# ── Start services ───────────────────────────────────────────────────────────
printf "%b%-10s%b Starting on :8080\n" "${CYAN}"    "[API]"     "${RESET}"
printf "%b%-10s%b Starting on :8100\n" "${YELLOW}"  "[SIDECAR]" "${RESET}"
printf "%b%-10s%b Starting on :3000\n" "${MAGENTA}" "[UI]"      "${RESET}"
printf "\n"

run_service "${CYAN}" "[API]" \
  bash -c "cd '${REPO_ROOT}/api' && LOG_DIR='${REPO_ROOT}/api/logs/dev' ./gradlew bootRun"

run_service "${YELLOW}" "[SIDECAR]" \
  bash -c "cd '${REPO_ROOT}/ml-sidecar' && LOG_DIR='${REPO_ROOT}/ml-sidecar/logs/dev' uv run uvicorn app.main:app --host 0.0.0.0 --port 8100"

run_service "${MAGENTA}" "[UI]" \
  bash -c "cd '${REPO_ROOT}/frontend' && npm run dev"

# ── Wait for all services ────────────────────────────────────────────────────
for pid in "${PIDS[@]}"; do
  wait "${pid}"
done
