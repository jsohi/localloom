#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE_FLAGS="-f $REPO_ROOT/docker-compose.yml"
EXPORT_DIR="$REPO_ROOT/logs/export-$(date +%Y%m%d-%H%M%S)"

source "$REPO_ROOT/scripts/_collect-logs.sh"

echo "==> Exporting logs..."

if docker compose $COMPOSE_FLAGS ps -q api >/dev/null 2>&1; then
  collect_logs "$COMPOSE_FLAGS" "$EXPORT_DIR"
else
  echo "    Docker services not running, checking local log files..."
  mkdir -p "$EXPORT_DIR"
  cp "$REPO_ROOT/logs/"*.log "$EXPORT_DIR/" 2>/dev/null || echo "    No local log files found."
fi

if [ -d "$REPO_ROOT/frontend/e2e-results" ]; then
  cp -r "$REPO_ROOT/frontend/e2e-results" "$EXPORT_DIR/e2e-results/" 2>/dev/null || true
fi
