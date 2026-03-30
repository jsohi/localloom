#!/usr/bin/env bash
set -euo pipefail

# ── Color helpers ────────────────────────────────────────────────────────────
GREEN='\033[0;32m'
RED='\033[0;31m'
BOLD='\033[1m'
RESET='\033[0m'

ok()   { printf "  ${GREEN}✔${RESET}  %s\n" "$*"; }
fail() { printf "  ${RED}✘${RESET}  %s\n" "$*"; }
header() { printf "\n${BOLD}%s${RESET}\n" "$*"; }

# ── Version utilities ────────────────────────────────────────────────────────

# Extract the first sequence of digits.digits from a string.
# Usage: extract_version "openjdk 25.0.1 2025-10-21" -> "25"
major_version() {
  local raw="$1"
  # Grab the first token that looks like N or N.M or N.M.P
  local token
  token=$(printf '%s' "$raw" | grep -oE '[0-9]+\.[0-9]+(\.[0-9]+)?' | head -1)
  # Return the major component
  printf '%s' "${token%%.*}"
}

# Compare major version against a minimum.
# Returns 0 (true) if actual >= required.
version_ge() {
  local actual="$1"
  local required="$2"
  [ "${actual}" -ge "${required}" ] 2>/dev/null
}

# ── Dependency checks ────────────────────────────────────────────────────────
header "Checking system dependencies"

MISSING=()

check_java() {
  local required=25
  if command -v java >/dev/null 2>&1; then
    local raw
    raw=$(java --version 2>&1 | head -1)
    local maj
    maj=$(major_version "$raw")
    if version_ge "${maj}" "${required}"; then
      ok "Java ${maj} (>= ${required} required)  —  ${raw}"
    else
      fail "Java ${maj} found but >= ${required} required  —  ${raw}"
      MISSING+=("java >= ${required}")
    fi
  else
    fail "java not found (>= ${required} required)"
    MISSING+=("java >= ${required}")
  fi
}

check_python() {
  local required=11  # major.minor: we check minor for Python 3.x
  if command -v python3 >/dev/null 2>&1; then
    local raw
    raw=$(python3 --version 2>&1)
    # For Python 3.x, extract the minor version component
    local ver
    ver=$(printf '%s' "$raw" | grep -oE '[0-9]+\.[0-9]+(\.[0-9]+)?' | head -1)
    local minor="${ver#*.}"
    minor="${minor%%.*}"
    local major="${ver%%.*}"
    if [ "${major}" -ge 3 ] && [ "${minor}" -ge "${required}" ]; then
      ok "Python ${ver} (>= 3.${required} required)  —  ${raw}"
    else
      fail "Python ${ver} found but >= 3.${required} required  —  ${raw}"
      MISSING+=("python3 >= 3.${required}")
    fi
  else
    fail "python3 not found (>= 3.${required} required)"
    MISSING+=("python3 >= 3.${required}")
  fi
}

check_node() {
  local required=20
  if command -v node >/dev/null 2>&1; then
    local raw
    raw=$(node --version 2>&1)
    local maj
    maj=$(major_version "$raw")
    if version_ge "${maj}" "${required}"; then
      ok "Node ${maj} (>= ${required} required)  —  ${raw}"
    else
      fail "Node ${maj} found but >= ${required} required  —  ${raw}"
      MISSING+=("node >= ${required}")
    fi
  else
    fail "node not found (>= ${required} required)"
    MISSING+=("node >= ${required}")
  fi
}

check_ffmpeg() {
  if command -v ffmpeg >/dev/null 2>&1; then
    local raw
    raw=$(ffmpeg -version 2>&1 | head -1)
    ok "ffmpeg found  —  ${raw}"
  else
    fail "ffmpeg not found"
    MISSING+=("ffmpeg")
  fi
}

check_ytdlp() {
  if command -v yt-dlp >/dev/null 2>&1; then
    local raw
    raw=$(yt-dlp --version 2>&1 | head -1)
    ok "yt-dlp ${raw}"
  else
    fail "yt-dlp not found"
    MISSING+=("yt-dlp")
  fi
}

check_ollama() {
  if command -v ollama >/dev/null 2>&1; then
    local raw
    raw=$(ollama --version 2>&1 | head -1)
    ok "ollama found  —  ${raw}"
  else
    fail "ollama not found"
    MISSING+=("ollama")
  fi
}

check_java
check_python
check_node
check_ffmpeg
check_ytdlp
check_ollama

# Abort early if any dep is missing
if [ ${#MISSING[@]} -gt 0 ]; then
  printf "\n${RED}${BOLD}Missing dependencies:${RESET}\n"
  for dep in "${MISSING[@]}"; do
    printf "  • %s\n" "${dep}"
  done
  printf "\nInstall the dependencies above and re-run this script.\n\n"
  exit 1
fi

# ── Project installation ─────────────────────────────────────────────────────
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

header "Installing API dependencies  (Spring Boot / Gradle)"
if [ -f "${REPO_ROOT}/api/gradlew" ]; then
  (cd "${REPO_ROOT}/api" && ./gradlew build -x test)
  ok "API build complete"
else
  printf "  Skipped — %s/api/gradlew not found\n" "${REPO_ROOT}"
fi

header "Installing ML sidecar dependencies  (Python / uv)"
if [ -f "${REPO_ROOT}/ml-sidecar/pyproject.toml" ]; then
  (cd "${REPO_ROOT}/ml-sidecar" && uv sync)
  ok "ML sidecar dependencies synced"
else
  printf "  Skipped — %s/ml-sidecar/pyproject.toml not found\n" "${REPO_ROOT}"
fi

header "Installing frontend dependencies  (Node / npm)"
if [ -f "${REPO_ROOT}/frontend/package.json" ]; then
  (cd "${REPO_ROOT}/frontend" && npm install)
  ok "Frontend dependencies installed"
else
  printf "  Skipped — %s/frontend/package.json not found\n" "${REPO_ROOT}"
fi

printf "\n${GREEN}${BOLD}Setup complete.${RESET}  Run \`make dev\` to start all services.\n\n"
