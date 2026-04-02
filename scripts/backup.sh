#!/usr/bin/env bash
set -euo pipefail

# Back up PostgreSQL and ChromaDB data to ./backups/
# Keeps the last 7 backups, deletes older ones.

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BACKUP_DIR="$REPO_ROOT/backups"
TIMESTAMP=$(date +%Y%m%d-%H%M%S)
KEEP=20

mkdir -p "$BACKUP_DIR"

echo "==> Backing up LocalLoom data ($TIMESTAMP)..."

# PostgreSQL dump
PG_CONTAINER=$(docker compose -f "$REPO_ROOT/docker-compose.yml" ps -q postgres 2>/dev/null || true)
if [ -n "$PG_CONTAINER" ]; then
  PG_FILE="$BACKUP_DIR/postgres-$TIMESTAMP.sql.gz"
  docker exec "$PG_CONTAINER" pg_dump -U "${POSTGRES_USER:-localloom}" "${POSTGRES_DB:-localloom}" \
    | gzip > "$PG_FILE"
  echo "    Postgres: $PG_FILE ($(du -h "$PG_FILE" | cut -f1))"
else
  echo "    Postgres: skipped (container not running)"
fi

# ChromaDB volume backup
CHROMA_VOLUME="localloom_chroma-data"
if docker volume inspect "$CHROMA_VOLUME" &>/dev/null; then
  CHROMA_FILE="$BACKUP_DIR/chromadb-$TIMESTAMP.tar.gz"
  docker run --rm \
    -v "$CHROMA_VOLUME":/data:ro \
    -v "$BACKUP_DIR":/backup \
    alpine tar czf "/backup/chromadb-$TIMESTAMP.tar.gz" -C /data .
  echo "    ChromaDB: $CHROMA_FILE ($(du -h "$CHROMA_FILE" | cut -f1))"
else
  echo "    ChromaDB: skipped (volume not found)"
fi

# Purge old backups (keep last N)
for prefix in postgres chromadb; do
  ls -t "$BACKUP_DIR/$prefix"-* 2>/dev/null | tail -n +$((KEEP + 1)) | xargs rm -f 2>/dev/null || true
done

TOTAL=$(du -sh "$BACKUP_DIR" | cut -f1)
COUNT=$(ls "$BACKUP_DIR"/*.gz 2>/dev/null | wc -l | tr -d ' ')
echo "==> Backup complete: $COUNT file(s), $TOTAL total"
