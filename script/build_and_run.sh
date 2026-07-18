#!/usr/bin/env bash
set -euo pipefail

MODE="${1:-run}"
APP_NAME="PixivShaft"
MAIN_CLASS="ceui.pixiv.MainKt"
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUN_LOG="/tmp/pixiv-shaft-run.log"

cd "$ROOT_DIR"
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@21}"

run_app() {
  ./gradlew :app:run --no-daemon
}

start_app() {
  : > "$RUN_LOG"
  ./gradlew :app:run --no-daemon >"$RUN_LOG" 2>&1 &
  RUN_PID=$!
}

cleanup() {
  if [[ -n "${APP_PID:-}" && "$APP_PID" != "${RUN_PID:-}" ]]; then
    kill "$APP_PID" >/dev/null 2>&1 || true
  fi
  if [[ -n "${RUN_PID:-}" ]]; then
    kill "$RUN_PID" >/dev/null 2>&1 || true
  fi
}

find_app_pid() {
  local parent_pid="$1"
  local child_pid
  local command_line

  while IFS= read -r child_pid; do
    command_line="$(ps -p "$child_pid" -o command= 2>/dev/null || true)"
    if [[ "$command_line" == *"$MAIN_CLASS"* ]]; then
      printf '%s\n' "$child_pid"
      return 0
    fi
    if find_app_pid "$child_pid"; then
      return 0
    fi
  done < <(pgrep -P "$parent_pid" 2>/dev/null || true)

  return 1
}

stream_run_log() {
  start_app
  trap cleanup EXIT
  tail -f "$RUN_LOG"
}

case "$MODE" in
  run)
    run_app
    ;;
  --debug|debug)
    ./gradlew :app:run --no-daemon --debug
    ;;
  --logs|logs)
    stream_run_log
    ;;
  --telemetry|telemetry)
    stream_run_log
    ;;
  --verify|verify)
    start_app
    trap cleanup EXIT
    for _ in {1..30}; do
      if APP_PID="$(find_app_pid "$RUN_PID")"; then
        echo "$APP_NAME is running (PID $APP_PID)"
        exit 0
      fi
      if ! kill -0 "$RUN_PID" >/dev/null 2>&1; then
        break
      fi
      sleep 1
    done
    echo "Application did not start. Last output:" >&2
    tail -40 /tmp/pixiv-shaft-run.log >&2 || true
    exit 1
    ;;
  *)
    echo "usage: $0 [run|--debug|--logs|--telemetry|--verify]" >&2
    exit 2
    ;;
esac
