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

# Resolve branch separately so `set -e` actually fires if `git branch
# --show-current` errors. (Inline `CURRENT_BRANCH=$(git ...)` masks the
# exit code, letting an empty value silently bypass the main-branch check.)
CURRENT_BRANCH=""
CURRENT_BRANCH="$(git branch --show-current)"
if [[ -z "$CURRENT_BRANCH" ]]; then
  echo "ERROR: Could not determine current git branch."
  exit 1
fi
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
# All format/lint/test commands run unsuppressed. If a per-language tool is
# legitimately broken (e.g., a known-flaky pytest), the right fix is to ignore
# it in that tool's own config (pyproject.toml, eslintrc, etc.) — not to
# silence it here. Suppression hides real failures.
echo "==> Step 2/5: Formatting"
(cd api && ./gradlew spotlessApply)
(cd ml-sidecar && uv run ruff format .)
(cd frontend && npm run format)

# ── Step 3/5: Linting ───────────────────────────────────────────────────────
echo "==> Step 3/5: Linting"
(cd api && ./gradlew spotlessCheck)
(cd ml-sidecar && uv run ruff check .)
(cd frontend && npm run lint)

# ── Step 4/5: Running tests ─────────────────────────────────────────────────
echo "==> Step 4/5: Running tests"
(cd api && ./gradlew test)
(cd ml-sidecar && uv run pytest --ignore=tests/ml)
(cd frontend && npx vitest run)

# ── Step 5/5: Pushing ───────────────────────────────────────────────────────
# Signal the scripts/hooks/pre-push hook that the format+lint+test pipeline
# already ran here, so it should let the push through. Any direct `git push`
# without this env var is refused by the hook.
echo "==> Step 5/5: Pushing"
LOCALLOOM_PUSH_VIA_MAKE=1 git push --force-with-lease -u origin "$CURRENT_BRANCH"

echo "==> Push complete."
