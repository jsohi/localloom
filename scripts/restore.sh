#!/usr/bin/env bash
set -euo pipefail

# Restore PostgreSQL and ChromaDB data from a backup.
# Usage:
#   scripts/restore.sh                  # interactive — lists backups, prompts for selection
#   scripts/restore.sh 20260402-103926  # restore specific timestamp

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BACKUP_DIR="$REPO_ROOT/backups"

if [ ! -d "$BACKUP_DIR" ]; then
  echo "No backups directory found at $BACKUP_DIR"
  exit 1
fi

# List available backup timestamps
list_backups() {
  ls "$BACKUP_DIR"/postgres-*.sql.gz 2>/dev/null \
    | sed 's|.*postgres-||; s|\.sql\.gz||' \
    | sort -r
}

TIMESTAMPS=$(list_backups)
if [ -z "$TIMESTAMPS" ]; then
  echo "No backups found in $BACKUP_DIR"
  exit 1
fi

# Select timestamp
if [ -n "${1:-}" ]; then
  TIMESTAMP="$1"
else
  echo "Available backups:"
  echo "$TIMESTAMPS" | nl -ba
  echo ""
  printf "Enter backup number (or timestamp): "
  read -r SELECTION
  if [[ "$SELECTION" =~ ^[0-9]+$ ]] && [ "$SELECTION" -le "$(echo "$TIMESTAMPS" | wc -l | tr -d ' ')" ]; then
    TIMESTAMP=$(echo "$TIMESTAMPS" | sed -n "${SELECTION}p")
  else
    TIMESTAMP="$SELECTION"
  fi
fi

PG_FILE="$BACKUP_DIR/postgres-$TIMESTAMP.sql.gz"
CHROMA_FILE="$BACKUP_DIR/chromadb-$TIMESTAMP.tar.gz"

if [ ! -f "$PG_FILE" ]; then
  echo "Postgres backup not found: $PG_FILE"
  exit 1
fi

echo "==> Restoring from backup: $TIMESTAMP"
echo "    Postgres: $PG_FILE"
echo "    ChromaDB: $CHROMA_FILE"
echo ""
printf "This will OVERWRITE current data. Continue? [y/N] "
read -r CONFIRM
if [ "$CONFIRM" != "y" ] && [ "$CONFIRM" != "Y" ]; then
  echo "Aborted."
  exit 0
fi

# Stop app services (keep Docker running)
echo "==> Stopping app services..."
lsof -ti :8080 2>/dev/null | xargs kill -9 2>/dev/null || true
lsof -ti :8100 2>/dev/null | xargs kill -9 2>/dev/null || true
lsof -ti :3000 2>/dev/null | xargs kill -9 2>/dev/null || true
sleep 1

# Ensure Postgres is running
docker compose -f "$REPO_ROOT/docker-compose.yml" up -d postgres 2>/dev/null
sleep 3

# Restore Postgres
PG_CONTAINER=$(docker compose -f "$REPO_ROOT/docker-compose.yml" ps -q postgres)
echo "==> Restoring PostgreSQL..."
gunzip -c "$PG_FILE" | docker exec -i "$PG_CONTAINER" psql -U "${POSTGRES_USER:-localloom}" -d "${POSTGRES_DB:-localloom}" -q --set ON_ERROR_STOP=on 2>&1 | tail -1 || true
echo "    PostgreSQL restored."

# Restore ChromaDB
if [ -f "$CHROMA_FILE" ]; then
  CHROMA_VOLUME="localloom_chroma-data"
  echo "==> Restoring ChromaDB..."
  # Stop ChromaDB to avoid conflicts
  docker compose -f "$REPO_ROOT/docker-compose.yml" stop chromadb 2>/dev/null || true
  # Clear and restore volume
  docker run --rm -v "$CHROMA_VOLUME":/data alpine sh -c "rm -rf /data/*"
  docker run --rm \
    -v "$CHROMA_VOLUME":/data \
    -v "$BACKUP_DIR":/backup:ro \
    alpine tar xzf "/backup/chromadb-$TIMESTAMP.tar.gz" -C /data
  echo "    ChromaDB restored."
else
  echo "    ChromaDB backup not found — skipped (re-sync will rebuild vectors)."
fi

echo ""
echo "==> Restore complete. Run 'make restart' to start the app."
