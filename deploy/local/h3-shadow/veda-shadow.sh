#!/usr/bin/env bash
set -euo pipefail

root=/home/neo/comfyui-h3-veda-039
comfy="$root/ComfyUI"
python=/home/neo/comfyui-fasth3-shadow/env-cu130/bin/python
pid_file="$root/comfy-8192.pid"
log_file="$root/comfy-8192.log"
port=8192

running_pid() {
  [[ -f "$pid_file" ]] || return 1
  local pid
  pid="$(cat "$pid_file")"
  [[ -n "$pid" && -r "/proc/$pid/cmdline" ]] || return 1
  tr '\0' ' ' < "/proc/$pid/cmdline" | grep -Fq "$comfy/main.py"
  tr '\0' ' ' < "/proc/$pid/cmdline" | grep -Fq -- "--port $port"
  printf '%s\n' "$pid"
}

start() {
  if pid="$(running_pid)"; then
    echo "Veda shadow is already running (pid=$pid, port=$port)"
    return
  fi
  nohup "$python" "$comfy/main.py" \
    --listen 0.0.0.0 \
    --port "$port" \
    --cuda-device 0 \
    --disable-async-offload \
    --reserve-vram 2 \
    --extra-model-paths-config /home/neo/comfyui-fasth3-shadow/extra_model_paths.yaml \
    > "$log_file" 2>&1 &
  echo "$!" > "$pid_file"
  echo "Veda shadow started (pid=$!, port=$port)"
}

stop() {
  if ! pid="$(running_pid)"; then
    echo "Veda shadow is not running"
    return
  fi
  kill "$pid"
  for _ in {1..15}; do
    running_pid >/dev/null 2>&1 || break
    sleep 1
  done
  if running_pid >/dev/null 2>&1; then
    echo "Veda shadow did not stop cleanly (pid=$pid)" >&2
    return 1
  fi
  rm -f "$pid_file"
  echo "Veda shadow stopped (pid=$pid)"
}

status() {
  if pid="$(running_pid)"; then
    curl -fsS "http://127.0.0.1:$port/system_stats"
    echo
    echo "Veda shadow is running (pid=$pid, port=$port)"
  else
    echo "Veda shadow is stopped"
    return 1
  fi
}

case "${1:-status}" in
  start) start ;;
  stop) stop ;;
  restart) stop || true; start ;;
  status) status ;;
  *) echo "Usage: $0 {start|stop|restart|status}" >&2; exit 2 ;;
esac
