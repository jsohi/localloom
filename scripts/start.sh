#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CHAT_MODEL="${OLLAMA_MODEL:-llama4:scout}"
EMBED_MODEL="${OLLAMA_EMBED_MODEL:-mxbai-embed-large}"
OLLAMA_STARTED_BY_SCRIPT=false
MODE="${1:-prod}"

# ── Color helpers ──────────────────────────────────────────────────────────
GREEN='\033[0;32m'
CYAN='\033[0;36m'
YELLOW='\033[0;33m'
MAGENTA='\033[0;35m'
RED='\033[0;31m'
BOLD='\033[1m'
RESET='\033[0m'

info()  { printf "${CYAN}==> %s${RESET}\n" "$*"; }
ok()    { printf "    ${GREEN}✔${RESET}  %s\n" "$*"; }
warn()  { printf "    ${YELLOW}⚠${RESET}  %s\n" "$*"; }
err()   { printf "    ${RED}✘${RESET}  %s\n" "$*"; }

usage() {
  cat <<EOF
${BOLD}Usage:${RESET} $0 [mode]

${BOLD}Modes:${RESET}
  prod    Start all services via Docker Compose (default)
  dev     Start infra in Docker, app services natively with hot reload
  stop    Stop all running services

${BOLD}Environment:${RESET}
  OLLAMA_MODEL    Chat model (default: llama4:scout)

${BOLD}Examples:${RESET}
  $0                          # Production mode
  $0 dev                      # Development mode
  $0 stop                     # Stop everything
  OLLAMA_MODEL=qwen2.5:7b $0  # Use a different model
EOF
  exit 0
}

[[ "${MODE}" == "-h" || "${MODE}" == "--help" ]] && usage

# ── Cleanup ────────────────────────────────────────────────────────────────
DEV_PIDS=()

cleanup() {
  echo ""
  info "Shutting down..."

  # Stop dev processes if any
  for pid in "${DEV_PIDS[@]:-}"; do
    kill "$pid" 2>/dev/null || true
  done
  wait 2>/dev/null || true

  # Stop Docker services
  if [[ "${MODE}" == "prod" ]]; then
    docker compose -f "$REPO_ROOT/docker-compose.yml" down 2>/dev/null || true
  elif [[ "${MODE}" == "dev" ]]; then
    docker compose -f "$REPO_ROOT/docker-compose.yml" down 2>/dev/null || true
  fi

  # Stop Ollama if we started it
  if [[ "$OLLAMA_STARTED_BY_SCRIPT" == true && -n "${OLLAMA_PID:-}" ]]; then
    ok "Stopping Ollama (PID $OLLAMA_PID)"
    kill -TERM "$OLLAMA_PID" 2>/dev/null && sleep 2
    kill -9 "$OLLAMA_PID" 2>/dev/null || true
  fi

  ok "All services stopped"
}
trap cleanup EXIT
trap 'exit 130' INT TERM

# Shared prereq helpers (require_command, ensure_docker_running,
# ensure_ollama_running, ensure_ollama_models, ensure_compose_services).
# shellcheck source=_prereqs.sh
source "$REPO_ROOT/scripts/_prereqs.sh"

# ── Stop mode ──────────────────────────────────────────────────────────────
if [[ "${MODE}" == "stop" ]]; then
  info "Stopping all services..."
  docker compose -f "$REPO_ROOT/docker-compose.yml" down 2>/dev/null || true
  # Kill any native dev processes
  pkill -f "gradlew bootRun" 2>/dev/null || true
  pkill -f "uvicorn app.main:app" 2>/dev/null || true
  pkill -f "next dev" 2>/dev/null || true
  ok "All services stopped"
  trap - EXIT  # skip cleanup since we already stopped
  exit 0
fi

# ── Prerequisites ──────────────────────────────────────────────────────────
info "Checking prerequisites..."

require_command docker "https://orbstack.dev or https://docker.com"
require_command ollama "https://ollama.com"

if [[ "${MODE}" == "dev" ]]; then
  require_command java "https://adoptium.net (JDK 25+)"
  require_command node "https://nodejs.org (Node 20+)"
  require_command uv   "https://docs.astral.sh/uv/"
fi

ok "Prerequisites OK"

info "Checking Docker daemon..."
ensure_docker_running

# ── Ollama ─────────────────────────────────────────────────────────────────
info "Checking Ollama..."
ensure_ollama_running
ensure_ollama_models "$CHAT_MODEL" "$EMBED_MODEL"

# ── Production mode ────────────────────────────────────────────────────────
if [[ "${MODE}" == "prod" ]]; then
  info "Starting all services via Docker Compose..."

  # Build images if not present
  if ! docker image inspect localloom/api:latest >/dev/null 2>&1 ||
     ! docker image inspect localloom/frontend:latest >/dev/null 2>&1 ||
     ! docker image inspect localloom/ml-sidecar:latest >/dev/null 2>&1; then
    info "Building Docker images (first run may take a few minutes)..."
    docker compose -f "$REPO_ROOT/docker-compose.yml" build
  fi

  docker compose -f "$REPO_ROOT/docker-compose.yml" up -d --wait --wait-timeout 180 || {
    err "Services failed to start. Logs:"
    docker compose -f "$REPO_ROOT/docker-compose.yml" logs --tail=30
    exit 1
  }

  echo ""
  printf "${GREEN}${BOLD}LocalLoom is running!${RESET}\n"
  echo ""
  echo "  App:        http://localhost:3000"
  echo "  API:        http://localhost:8080"
  echo "  ML Sidecar: http://localhost:8100"
  echo "  ChromaDB:   http://localhost:8000"
  echo "  PostgreSQL:  localhost:5432"
  echo ""
  echo "  Chat model:  $CHAT_MODEL"
  echo "  Embed model: $EMBED_MODEL"
  echo ""
  echo "  Stop with:   $0 stop"
  echo "  View logs:   docker compose logs -f"
  echo ""

  # Keep script running so Ctrl+C triggers cleanup
  echo "Press Ctrl+C to stop all services..."
  while true; do sleep 86400; done

# ── Development mode ───────────────────────────────────────────────────────
elif [[ "${MODE}" == "dev" ]]; then
  info "Starting infrastructure via Docker Compose..."

  # Start only Postgres and ChromaDB
  docker compose -f "$REPO_ROOT/docker-compose.yml" up -d postgres chromadb --wait --wait-timeout 60 || {
    err "Infrastructure failed to start. Logs:"
    docker compose -f "$REPO_ROOT/docker-compose.yml" logs postgres chromadb --tail=20
    exit 1
  }
  ok "PostgreSQL :5432 and ChromaDB :8000 ready"

  info "Installing dependencies..."
  (cd "$REPO_ROOT/ml-sidecar" && uv sync --quiet)
  (cd "$REPO_ROOT/frontend" && npm install --silent)
  ok "Dependencies installed"

  info "Starting app services with hot reload..."
  echo ""

  # Color-prefixed output for each service
  (cd "$REPO_ROOT/api" && ./gradlew bootRun 2>&1 | while IFS= read -r line; do
    printf "${CYAN}%-12s${RESET} %s\n" "[API]" "$line"
  done) &
  DEV_PIDS+=($!)

  (cd "$REPO_ROOT/ml-sidecar" && uv run uvicorn app.main:app --host 0.0.0.0 --port 8100 --reload 2>&1 | while IFS= read -r line; do
    printf "${YELLOW}%-12s${RESET} %s\n" "[SIDECAR]" "$line"
  done) &
  DEV_PIDS+=($!)

  (cd "$REPO_ROOT/frontend" && npm run dev 2>&1 | while IFS= read -r line; do
    printf "${MAGENTA}%-12s${RESET} %s\n" "[FRONTEND]" "$line"
  done) &
  DEV_PIDS+=($!)

  # Wait a moment for services to start
  sleep 5
  echo ""
  printf "${GREEN}${BOLD}LocalLoom dev mode running!${RESET}\n"
  echo ""
  echo "  Frontend:   http://localhost:3000  (hot reload)"
  echo "  API:        http://localhost:8080  (spring-boot-devtools)"
  echo "  ML Sidecar: http://localhost:8100  (uvicorn --reload)"
  echo "  PostgreSQL:  localhost:5432  (Docker)"
  echo "  ChromaDB:   http://localhost:8000  (Docker)"
  echo ""
  echo "  Chat model:  $CHAT_MODEL"
  echo ""
  echo "  Press Ctrl+C to stop all services"
  echo ""

  # Wait for any process to exit
  for pid in "${DEV_PIDS[@]}"; do
    wait "$pid" 2>/dev/null || true
  done

else
  err "Unknown mode: ${MODE}"
  usage
fi
