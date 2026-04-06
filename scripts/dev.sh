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
OLLAMA_STARTED_BY_SCRIPT=false

cleanup() {
  printf "\n%bShutting down services…%b\n" "${BOLD}" "${RESET}"
  for pid in "${PIDS[@]:-}"; do
    if kill -0 "${pid}" 2>/dev/null; then
      kill "${pid}" 2>/dev/null || true
    fi
  done
  if [[ "$OLLAMA_STARTED_BY_SCRIPT" == true && -n "${OLLAMA_PID:-}" ]]; then
    kill -TERM "$OLLAMA_PID" 2>/dev/null && sleep 1
    kill -9 "$OLLAMA_PID" 2>/dev/null || true
  fi
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

# ── Prerequisites ────────────────────────────────────────────────────────────
# Shared helpers (require_command, ensure_docker_running, ensure_ollama_running,
# ensure_ollama_models, ensure_compose_services).
# shellcheck source=_prereqs.sh
source "$REPO_ROOT/scripts/_prereqs.sh"

printf "==> Checking prerequisites...\n"
require_command docker "https://orbstack.dev or https://docker.com"
require_command java   "https://adoptium.net (JDK 25+)"
require_command node   "https://nodejs.org (Node 20+)"
require_command uv     "https://docs.astral.sh/uv/"
require_command ollama "https://ollama.com"

printf "==> Checking Docker daemon...\n"
ensure_docker_running

printf "==> Ensuring infra (postgres, chromadb) is up...\n"
ensure_compose_services "-f $REPO_ROOT/docker-compose.yml" postgres chromadb

printf "==> Checking Ollama...\n"
ensure_ollama_running

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
