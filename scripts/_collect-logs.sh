#!/usr/bin/env bash
# Shared log collection used by e2e.sh and logs-export.sh.
# Usage: source scripts/_collect-logs.sh
#        collect_logs <compose_flags> <output_dir>

collect_logs() {
  local compose_flags="$1"
  local log_dir="$2"

  mkdir -p "$log_dir"

  # Run all three copies in parallel, then wait on ONLY these PIDs. A bare `wait` would
  # also block on any other background child of the parent shell (e.g. the `ollama serve`
  # that e2e.sh starts via ensure_ollama_running), which never exits — that previously
  # caused e2e.sh to hang in __wait4 for 15+ minutes after tests completed.
  docker compose $compose_flags logs --timestamps > "$log_dir/docker-compose.log" 2>&1 &
  local logs_pid=$!
  docker compose $compose_flags cp api:/app/logs/. "$log_dir/api/" 2>/dev/null &
  local api_pid=$!
  docker compose $compose_flags cp ml-sidecar:/app/logs/. "$log_dir/ml-sidecar/" 2>/dev/null &
  local sidecar_pid=$!
  wait "$logs_pid" "$api_pid" "$sidecar_pid" || true

  local file_count total_size
  file_count=$(find "$log_dir" -type f | wc -l | tr -d ' ')
  total_size=$(du -sh "$log_dir" | cut -f1)

  echo ""
  echo "╔══════════════════════════════════════════════════════════════╗"
  echo "║  Logs ($file_count files, $total_size)"
  printf "║  %-60s║\n" "$log_dir"
  echo "╠══════════════════════════════════════════════════════════════╣"
  echo "║  docker-compose.log    All service stdout/stderr            ║"
  echo "║  api/api.log           Spring Boot application log          ║"
  echo "║  ml-sidecar/*.log      Python sidecar application log       ║"
  echo "╚══════════════════════════════════════════════════════════════╝"
  echo ""
  echo "  grep 'ERROR' $log_dir/**/*.log"
  echo "  grep '<request-id>' $log_dir/**/*.log"
  echo ""
}
