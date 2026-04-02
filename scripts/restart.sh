#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# Back up data before restart (best-effort — don't block restart on failure)
bash "$REPO_ROOT/scripts/backup.sh" || echo "    (backup skipped — non-critical)"

echo "==> Killing running services..."
lsof -ti :8080 2>/dev/null | xargs kill -9 2>/dev/null || true
lsof -ti :8100 2>/dev/null | xargs kill -9 2>/dev/null || true
lsof -ti :3000 2>/dev/null | xargs kill -9 2>/dev/null || true
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
