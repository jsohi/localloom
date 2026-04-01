#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
EXPORT_DIR="$REPO_ROOT/logs/export-$(date +%Y%m%d-%H%M%S)"

mkdir -p "$EXPORT_DIR"

echo "==> Exporting logs to $EXPORT_DIR"

# App-level log files (from Docker volume or local dev)
if docker compose -f "$REPO_ROOT/docker-compose.yml" ps -q api >/dev/null 2>&1; then
  echo "    Copying API logs from Docker volume..."
  docker compose -f "$REPO_ROOT/docker-compose.yml" cp api:/app/logs/. "$EXPORT_DIR/api/" 2>/dev/null || true

  echo "    Copying ML sidecar logs from Docker volume..."
  docker compose -f "$REPO_ROOT/docker-compose.yml" cp ml-sidecar:/app/logs/. "$EXPORT_DIR/ml-sidecar/" 2>/dev/null || true

  echo "    Capturing Docker stdout/stderr for all services..."
  docker compose -f "$REPO_ROOT/docker-compose.yml" logs --timestamps > "$EXPORT_DIR/docker-compose.log" 2>&1
else
  echo "    Docker services not running, checking local log files..."
  if [ -d "$REPO_ROOT/logs" ]; then
    cp -r "$REPO_ROOT/logs/"*.log "$EXPORT_DIR/" 2>/dev/null || true
  fi
fi

# E2E results if they exist
if [ -d "$REPO_ROOT/frontend/e2e-results" ]; then
  echo "    Copying E2E test results..."
  cp -r "$REPO_ROOT/frontend/e2e-results" "$EXPORT_DIR/e2e-results/" 2>/dev/null || true
fi

# Summary
FILE_COUNT=$(find "$EXPORT_DIR" -type f | wc -l | tr -d ' ')
TOTAL_SIZE=$(du -sh "$EXPORT_DIR" | cut -f1)
echo ""
echo "    Exported $FILE_COUNT files ($TOTAL_SIZE) to:"
echo "    $EXPORT_DIR"
echo ""
echo "    Search across all logs:"
echo "      grep 'ERROR' $EXPORT_DIR/**/*.log"
echo "      grep '<request-id>' $EXPORT_DIR/**/*.log"
