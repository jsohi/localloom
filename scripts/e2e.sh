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

# ── Helpers ─────────────────────────────────────────────────────────────────

# require_command <binary> <install-url>
# Fails fast if a binary is not on PATH. Cannot auto-install — OS-specific.
require_command() {
  local bin="$1" url="$2"
  if ! command -v "$bin" >/dev/null 2>&1; then
    echo "ERROR: '$bin' not found on PATH. Install: $url"
    exit 1
  fi
}

# ensure_docker_running
# Checks `docker info` and, if the daemon is down, attempts to start a Docker
# engine. macOS: prefers OrbStack, falls back to Docker Desktop. Linux: tries
# `systemctl start docker`. Other OSes: fails with a clear message.
ensure_docker_running() {
  if docker info >/dev/null 2>&1; then
    echo "    Docker daemon already running"
    return 0
  fi

  echo "    Docker daemon is not running, attempting to start..."

  case "$(uname -s)" in
    Darwin)
      if [ -d "/Applications/OrbStack.app" ]; then
        echo "    Starting OrbStack..."
        open -a OrbStack
      elif [ -d "/Applications/Docker.app" ]; then
        echo "    Starting Docker Desktop..."
        open -a Docker
      else
        echo "ERROR: No Docker engine found in /Applications. Install OrbStack (https://orbstack.dev) or Docker Desktop (https://docker.com)."
        exit 1
      fi
      ;;
    Linux)
      if command -v systemctl >/dev/null 2>&1; then
        echo "    Starting docker.service via systemctl..."
        sudo systemctl start docker || { echo "ERROR: failed to start docker.service"; exit 1; }
      else
        echo "ERROR: Docker daemon not running and no systemctl available. Start it manually."
        exit 1
      fi
      ;;
    *)
      echo "ERROR: Unsupported OS '$(uname -s)'. Start Docker manually."
      exit 1
      ;;
  esac

  # Wait up to 60s for the daemon to become reachable.
  local i
  for i in $(seq 1 60); do
    if docker info >/dev/null 2>&1; then
      echo "    Docker daemon ready (after ${i}s)"
      return 0
    fi
    sleep 1
  done
  echo "ERROR: Docker daemon failed to become ready within 60s"
  exit 1
}

# ensure_ollama_running
# Checks the Ollama HTTP API and, if unreachable, runs `ollama serve` in the
# background. Sets OLLAMA_STARTED_BY_SCRIPT=true so cleanup() can stop it.
ensure_ollama_running() {
  if curl -sf --max-time 5 http://localhost:11434/api/tags >/dev/null 2>&1; then
    echo "    Ollama already running"
    return 0
  fi

  echo "    Ollama not running, starting..."
  ollama serve &>/dev/null &
  OLLAMA_PID=$!
  OLLAMA_STARTED_BY_SCRIPT=true

  local i
  for i in $(seq 1 10); do
    if curl -sf --max-time 2 http://localhost:11434/api/tags >/dev/null 2>&1; then
      echo "    Ollama started (PID $OLLAMA_PID)"
      return 0
    fi
    sleep 1
  done
  echo "ERROR: Ollama failed to start after 10 retries"
  exit 1
}

# ensure_ollama_models <model> [<model> ...]
# Pulls each model if it is not already present locally.
ensure_ollama_models() {
  local model
  for model in "$@"; do
    if ollama list 2>/dev/null | awk '{print $1}' | grep -qx "$model"; then
      echo "    Model $model ready"
    else
      echo "    Pulling $model (this may take a few minutes)..."
      ollama pull "$model"
    fi
  done
}

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
