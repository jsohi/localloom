#!/usr/bin/env bash
# scripts/push.sh — pre-flight format + lint + test, then git push.
#
# This is the canonical pre-push pipeline. The Makefile `push` target
# delegates here. Use `make push` (not raw `git push`) so the formatting,
# linting, and test gates always run.
#
# Refuses to push to main/master.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

# Shared prereq helpers (require_command, ensure_docker_running, etc.).
# shellcheck source=_prereqs.sh
source "$REPO_ROOT/scripts/_prereqs.sh"

CURRENT_BRANCH="$(git branch --show-current)"
if [[ "$CURRENT_BRANCH" == "main" || "$CURRENT_BRANCH" == "master" ]]; then
  echo "ERROR: Do not push directly to $CURRENT_BRANCH. Create a feature branch first."
  exit 1
fi

# ── Step 1/5: Prerequisites ─────────────────────────────────────────────────
# Java integration tests use Testcontainers, which needs a running Docker
# daemon. Auto-start it (OrbStack on macOS, etc.) so `make push` is a true
# single-command preflight.
echo "==> Step 1/5: Checking prerequisites"
require_command docker "https://orbstack.dev or https://docker.com"
ensure_docker_running

# ── Step 2/5: Formatting ────────────────────────────────────────────────────
echo "==> Step 2/5: Formatting"
(cd api && ./gradlew spotlessApply)
(cd ml-sidecar && uv run ruff format . 2>/dev/null || true)
(cd frontend && npm run format 2>/dev/null || true)

# ── Step 3/5: Linting ───────────────────────────────────────────────────────
echo "==> Step 3/5: Linting"
(cd api && ./gradlew spotlessCheck)
(cd ml-sidecar && uv run ruff check . 2>/dev/null || true)
(cd frontend && npm run lint)

# ── Step 4/5: Running tests ─────────────────────────────────────────────────
echo "==> Step 4/5: Running tests"
(cd api && ./gradlew test)
(cd ml-sidecar && uv run pytest --ignore=tests/ml 2>/dev/null || true)
(cd frontend && npx vitest run)

# ── Step 5/5: Pushing ───────────────────────────────────────────────────────
echo "==> Step 5/5: Pushing"
git push --force-with-lease -u origin "$CURRENT_BRANCH"

echo "==> Push complete."
