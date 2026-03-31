#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE_FILES="-f $REPO_ROOT/docker-compose.yml -f $REPO_ROOT/docker-compose.test.yml"

cleanup() {
  echo "==> Stopping services..."
  docker compose $COMPOSE_FILES down 2>/dev/null || true
}
trap cleanup EXIT
trap 'exit 130' INT TERM

# Prerequisites
command -v docker >/dev/null 2>&1 || { echo "ERROR: Docker not installed"; exit 1; }
command -v npx >/dev/null 2>&1 || { echo "ERROR: npx not found"; exit 1; }

echo "==> Starting services..."
docker compose $COMPOSE_FILES up -d --wait || {
  echo "ERROR: Services failed to start. Logs:"
  docker compose $COMPOSE_FILES logs --tail=20
  exit 1
}

echo "==> Running E2E tests..."
cd "$REPO_ROOT/frontend"
npx playwright test "$@"
