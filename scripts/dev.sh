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

# Kill all background children on exit (Ctrl-C, error, or normal exit).
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
# Usage: run_service COLOR "[LABEL]" cmd args...
# Pipes the service's combined stdout+stderr through a prefixer and runs it in
# the background. Appends the background PID to PIDS[].
run_service() {
  local color="$1"; shift
  local label="$1"; shift

  # Use a subshell so the pipe stays entirely in the background.
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

# API — Spring Boot on :8080
run_service "${CYAN}" "[API]" \
  bash -c "cd '${REPO_ROOT}/api' && ./gradlew bootRun"

# ML Sidecar — Python FastAPI on :8100
run_service "${YELLOW}" "[SIDECAR]" \
  bash -c "cd '${REPO_ROOT}/ml-sidecar' && uv run uvicorn app.main:app --host 0.0.0.0 --port 8100 --reload"

# Frontend — Next.js on :3000
run_service "${MAGENTA}" "[UI]" \
  bash -c "cd '${REPO_ROOT}/frontend' && npm run dev"

# ── Wait for all services ────────────────────────────────────────────────────
# wait with individual PIDs so a failing service propagates its exit code.
for pid in "${PIDS[@]}"; do
  wait "${pid}"
done
