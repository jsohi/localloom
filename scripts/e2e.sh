#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE_FILES="-p localloom-test -f $REPO_ROOT/docker-compose.yml -f $REPO_ROOT/docker-compose.test.yml"
CHAT_MODEL="${E2E_OLLAMA_MODEL:-llama3.1:8b}"
EMBED_MODEL="${OLLAMA_EMBED_MODEL:-mxbai-embed-large}"
OLLAMA_STARTED_BY_SCRIPT=false

cleanup() {
  echo "==> Stopping services..."
  docker compose $COMPOSE_FILES down 2>/dev/null || true
  if [[ "$OLLAMA_STARTED_BY_SCRIPT" == true && -n "${OLLAMA_PID:-}" ]]; then
    echo "    Stopping Ollama (PID $OLLAMA_PID)..."
    kill -TERM "$OLLAMA_PID" 2>/dev/null && sleep 2
    kill -9 "$OLLAMA_PID" 2>/dev/null || true
  fi
}
trap cleanup EXIT
trap 'exit 130' INT TERM

# Shared prereq helpers (require_command, ensure_docker_running,
# ensure_ollama_running, ensure_ollama_models, ensure_compose_services).
# shellcheck source=_prereqs.sh
source "$REPO_ROOT/scripts/_prereqs.sh"

# ── Prerequisites ───────────────────────────────────────────────────────────
echo "==> Checking prerequisites..."

# Required binaries (cannot be auto-installed — OS-specific package managers).
require_command docker "https://orbstack.dev or https://docker.com"
require_command npx    "https://nodejs.org (Node.js 20+)"
require_command ollama "https://ollama.com"

# Auto-startable services.
echo "==> Checking Docker daemon..."
ensure_docker_running

echo "==> Checking Ollama..."
ensure_ollama_running
ensure_ollama_models "$CHAT_MODEL" "$EMBED_MODEL"

# ── Docker services ─────────────────────────────────────────────────────────
echo "==> Stopping any dev containers to free ports..."
docker compose -p localloom down 2>/dev/null || true
echo "==> Cleaning E2E volumes (fresh DB for migration consistency)..."
docker compose $COMPOSE_FILES down -v 2>/dev/null || true
echo "==> Building and starting Docker services..."
docker compose $COMPOSE_FILES build || { echo "ERROR: Docker build failed"; exit 1; }
docker compose $COMPOSE_FILES up -d --wait --wait-timeout 120 || {
  echo "ERROR: Services failed to start. Logs:"
  docker compose $COMPOSE_FILES logs --tail=20
  exit 1
}
echo "    All services healthy"

# ── Frontend dependencies ───────────────────────────────────────────────────
echo "==> Installing frontend dependencies..."
cd "$REPO_ROOT/frontend"
npm ci || { echo "ERROR: npm ci failed. Check package-lock.json"; exit 1; }

echo "==> Ensuring Playwright browsers are installed..."
npx playwright install chromium

# ── Run tests ───────────────────────────────────────────────────────────────
echo "==> Running E2E tests..."
echo "    Model: $CHAT_MODEL"
echo "    Base URL: http://localhost:13000"
echo ""

TEST_EXIT=0
BASE_URL=http://localhost:13000 API_URL=http://localhost:18080 npx playwright test "$@" || TEST_EXIT=$?

# ── Collect all logs ───────────────────────────────────────────────────────
source "$REPO_ROOT/scripts/_collect-logs.sh"

# Remove old log dirs (keep only the latest run)
find "${TMPDIR}" -maxdepth 1 -name "localloom-e2e-logs-*" -type d -exec rm -rf {} + 2>/dev/null || true

LOG_DIR="$TMPDIR/localloom-e2e-logs-$(date +%Y%m%d-%H%M%S)"
echo ""
echo "==> Collecting logs..."
collect_logs "$COMPOSE_FILES" "$LOG_DIR"

if [ -d "$REPO_ROOT/frontend/e2e-results" ]; then
  cp -r "$REPO_ROOT/frontend/e2e-results" "$LOG_DIR/playwright/"
fi

exit $TEST_EXIT
