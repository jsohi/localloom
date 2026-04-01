#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE_FILES="-p localloom-test -f $REPO_ROOT/docker-compose.yml -f $REPO_ROOT/docker-compose.test.yml"
CHAT_MODEL="${E2E_OLLAMA_MODEL:-llama3.1:8b}"
EMBED_MODEL="nomic-embed-text"
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

# ── Prerequisites ───────────────────────────────────────────────────────────
echo "==> Checking prerequisites..."

command -v docker >/dev/null 2>&1 || { echo "ERROR: Docker not installed. Install Docker Desktop: https://docker.com"; exit 1; }
command -v npx >/dev/null 2>&1 || { echo "ERROR: npx not found. Install Node.js 20+: https://nodejs.org"; exit 1; }
command -v ollama >/dev/null 2>&1 || { echo "ERROR: Ollama not installed. Install: https://ollama.com"; exit 1; }

docker info >/dev/null 2>&1 || { echo "ERROR: Docker is not running. Start Docker Desktop."; exit 1; }

# ── Ollama ──────────────────────────────────────────────────────────────────
echo "==> Checking Ollama..."

if ! curl -sf --max-time 5 http://localhost:11434/api/tags >/dev/null 2>&1; then
  echo "    Starting Ollama..."
  ollama serve &>/dev/null &
  OLLAMA_PID=$!
  OLLAMA_STARTED_BY_SCRIPT=true

  # Wait with retry loop
  for i in $(seq 1 10); do
    if curl -sf --max-time 2 http://localhost:11434/api/tags >/dev/null 2>&1; then
      break
    fi
    if [[ $i -eq 10 ]]; then
      echo "ERROR: Ollama failed to start after 10 retries"; exit 1
    fi
    sleep 1
  done
  echo "    Ollama started (PID $OLLAMA_PID)"
else
  echo "    Ollama already running"
fi

# Pull models if not present
for model in "$CHAT_MODEL" "$EMBED_MODEL"; do
  if ! ollama list 2>/dev/null | awk '{print $1}' | grep -qx "$model"; then
    echo "    Pulling $model (this may take a few minutes)..."
    ollama pull "$model"
  else
    echo "    Model $model ready"
  fi
done

# ── Docker services ─────────────────────────────────────────────────────────
echo "==> Starting Docker services..."
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
echo "    Base URL: http://localhost:3000"
echo ""

TEST_EXIT=0
npx playwright test "$@" || TEST_EXIT=$?

# ── Collect all logs ───────────────────────────────────────────────────────
LOG_DIR="$TMPDIR/localloom-e2e-logs-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$LOG_DIR"

echo ""
echo "==> Collecting logs..."

# Docker stdout/stderr for all services
docker compose $COMPOSE_FILES logs --timestamps > "$LOG_DIR/docker-compose.log" 2>&1

# App-level log files from Docker volumes
docker compose $COMPOSE_FILES cp api:/app/logs/. "$LOG_DIR/api/" 2>/dev/null || true
docker compose $COMPOSE_FILES cp ml-sidecar:/app/logs/. "$LOG_DIR/ml-sidecar/" 2>/dev/null || true

# Playwright results
if [ -d "$REPO_ROOT/frontend/e2e-results" ]; then
  cp -r "$REPO_ROOT/frontend/e2e-results" "$LOG_DIR/playwright/"
fi

FILE_COUNT=$(find "$LOG_DIR" -type f | wc -l | tr -d ' ')
TOTAL_SIZE=$(du -sh "$LOG_DIR" | cut -f1)

echo ""
echo "╔══════════════════════════════════════════════════════════════╗"
echo "║  E2E Logs ($FILE_COUNT files, $TOTAL_SIZE)"
printf "║  %-60s║\n" "$LOG_DIR"
echo "╠══════════════════════════════════════════════════════════════╣"
echo "║  docker-compose.log    All service stdout/stderr            ║"
echo "║  api/api.log           Spring Boot application log          ║"
echo "║  ml-sidecar/*.log      Python sidecar application log       ║"
echo "║  playwright/           Test results, traces, screenshots    ║"
echo "╚══════════════════════════════════════════════════════════════╝"
echo ""
echo "  Quick commands:"
echo "    grep 'ERROR' $LOG_DIR/**/*.log"
echo "    grep '<request-id>' $LOG_DIR/**/*.log"
echo ""

exit $TEST_EXIT
