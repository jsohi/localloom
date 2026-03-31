#!/usr/bin/env bash
set -e

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"

echo "==> Starting services..."
docker compose -f "$REPO_ROOT/docker-compose.yml" -f "$REPO_ROOT/docker-compose.test.yml" up -d --wait

echo "==> Running E2E tests..."
cd "$REPO_ROOT/frontend" && npx playwright test "$@"
EXIT_CODE=$?

echo "==> Stopping services..."
docker compose -f "$REPO_ROOT/docker-compose.yml" -f "$REPO_ROOT/docker-compose.test.yml" down

exit $EXIT_CODE
