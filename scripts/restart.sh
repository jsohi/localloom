#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# Back up data before restart (best-effort — don't block restart on failure)
bash "$REPO_ROOT/scripts/backup.sh" || echo "    (backup skipped — non-critical)"

echo "==> Killing running services..."
# Kill port listeners and all their child processes (e.g. Whisper worker pool)
for port in 8080 8100 3000; do
  for pid in $(lsof -ti :"$port" 2>/dev/null); do
    # Kill the entire process group to catch child/worker processes
    pgid=$(ps -o pgid= -p "$pid" 2>/dev/null | tr -d ' ')
    if [ -n "$pgid" ] && [ "$pgid" != "0" ]; then
      kill -9 -"$pgid" 2>/dev/null || true
    fi
    kill -9 "$pid" 2>/dev/null || true
  done
done
# Also kill any orphaned Whisper worker processes
pgrep -f 'multiprocessing.spawn.*spawn_main' 2>/dev/null | xargs kill -9 2>/dev/null || true
sleep 1

echo "==> Starting backing services (PostgreSQL + ChromaDB)..."
docker compose -f "$REPO_ROOT/docker-compose.yml" up -d postgres chromadb 2>/dev/null || true

echo "==> Waiting for backing services..."
for i in $(seq 1 10); do
  if docker compose -f "$REPO_ROOT/docker-compose.yml" ps --format '{{.Status}}' 2>/dev/null | grep -q "healthy"; then
    break
  fi
  sleep 1
done

echo "==> Starting dev servers..."
exec make -C "$REPO_ROOT" dev
