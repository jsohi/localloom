# shellcheck shell=bash
# scripts/_prereqs.sh — shared prerequisite helpers for LocalLoom dev scripts.
#
# Source this file from another bash script:
#
#     source "$REPO_ROOT/scripts/_prereqs.sh"
#
# Functions provided:
#   require_command <bin> <install-url>
#       Fail-fast if a binary is missing from PATH. OS-agnostic.
#
#   ensure_docker_running
#       Ensure the Docker daemon is reachable. If not, attempt to start a
#       local engine (OrbStack → Docker Desktop on macOS, systemctl on Linux).
#       Waits up to 60s for the daemon to become reachable.
#
#   ensure_ollama_running
#       Ensure Ollama is reachable on http://localhost:11434. If not, start
#       `ollama serve` in the background and set OLLAMA_PID and
#       OLLAMA_STARTED_BY_SCRIPT=true so the caller's cleanup trap can stop it.
#
#   ensure_ollama_models <model> [<model> ...]
#       Pull each named Ollama model if it is not already cached locally.
#
#   ensure_compose_services <compose-args> <service> [<service> ...]
#       Bring up specific docker compose services in detached mode if they are
#       not already running. <compose-args> is the same string passed to
#       `docker compose`, e.g. "-f docker-compose.yml" or "-p localloom-test
#       -f docker-compose.yml -f docker-compose.test.yml".
#
# Design note: this file is intentionally idempotent — calling any function
# when the dependency is already satisfied is a near-no-op (just one log line).

# ── require_command ─────────────────────────────────────────────────────────
require_command() {
  local bin="$1" url="$2"
  if ! command -v "$bin" >/dev/null 2>&1; then
    echo "ERROR: '$bin' not found on PATH. Install: $url"
    exit 1
  fi
}

# ── ensure_docker_running ──────────────────────────────────────────────────
ensure_docker_running() {
  if docker info >/dev/null 2>&1; then
    echo "    Docker daemon already running"
    return 0
  fi

  echo "    Docker daemon is not running, attempting to start..."

  case "$(uname -s)" in
    Darwin)
      if [ -d "/Applications/OrbStack.app" ]; then
        echo "    Starting OrbStack..."
        open -a OrbStack
      elif [ -d "/Applications/Docker.app" ]; then
        echo "    Starting Docker Desktop..."
        open -a Docker
      else
        echo "ERROR: No Docker engine found in /Applications. Install OrbStack (https://orbstack.dev) or Docker Desktop (https://docker.com)."
        exit 1
      fi
      ;;
    Linux)
      if command -v systemctl >/dev/null 2>&1; then
        echo "    Starting docker.service via systemctl..."
        sudo systemctl start docker || { echo "ERROR: failed to start docker.service"; exit 1; }
      else
        echo "ERROR: Docker daemon not running and no systemctl available. Start it manually."
        exit 1
      fi
      ;;
    *)
      echo "ERROR: Unsupported OS '$(uname -s)'. Start Docker manually."
      exit 1
      ;;
  esac

  local i
  for i in $(seq 1 60); do
    if docker info >/dev/null 2>&1; then
      echo "    Docker daemon ready (after ${i}s)"
      return 0
    fi
    sleep 1
  done
  echo "ERROR: Docker daemon failed to become ready within 60s"
  exit 1
}

# ── ensure_ollama_running ──────────────────────────────────────────────────
ensure_ollama_running() {
  if curl -sf --max-time 5 http://localhost:11434/api/tags >/dev/null 2>&1; then
    echo "    Ollama already running"
    return 0
  fi

  echo "    Ollama not running, starting..."
  ollama serve &>/dev/null &
  OLLAMA_PID=$!
  OLLAMA_STARTED_BY_SCRIPT=true

  local i
  for i in $(seq 1 15); do
    if curl -sf --max-time 2 http://localhost:11434/api/tags >/dev/null 2>&1; then
      echo "    Ollama started (PID $OLLAMA_PID)"
      return 0
    fi
    sleep 1
  done
  echo "ERROR: Ollama failed to start after 15 retries"
  exit 1
}

# ── ensure_ollama_models ───────────────────────────────────────────────────
ensure_ollama_models() {
  local model
  for model in "$@"; do
    if ollama list 2>/dev/null | awk '{print $1}' | grep -qx "$model"; then
      echo "    Model $model ready"
    else
      echo "    Pulling $model (this may take a few minutes)..."
      ollama pull "$model"
    fi
  done
}

# ── ensure_compose_services ────────────────────────────────────────────────
# Usage: ensure_compose_services "<compose-args>" service1 service2 ...
ensure_compose_services() {
  local compose_args="$1"; shift
  if [ "$#" -eq 0 ]; then
    echo "ERROR: ensure_compose_services called with no service names"
    exit 1
  fi

  echo "    Bringing up compose services: $*"
  # shellcheck disable=SC2086
  docker compose $compose_args up -d --wait --wait-timeout 60 "$@" || {
    echo "ERROR: Failed to bring up compose services: $*"
    # shellcheck disable=SC2086
    docker compose $compose_args logs --tail=20 "$@"
    exit 1
  }
  echo "    Compose services ready: $*"
}
