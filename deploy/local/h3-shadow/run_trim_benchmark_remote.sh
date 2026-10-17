#!/usr/bin/env bash
set -euo pipefail

host="${1:-neo@10.190.0.222}"
production_url="http://127.0.0.1:8188"
shadow_url="http://127.0.0.1:8192"
shadow_root="/home/neo/comfyui-fasth3-shadow"
model="fastvideo_fasth3_trim_8step_int8_convrot.safetensors"
video_vae="minimax_h3_video_vae_int8_convrot.safetensors"
stamp="$(date -u +%Y%m%dT%H%M%SZ)"
remote_log_dir="/mnt/disk2/models/minimax-h3-fastvideo/benchmarks/$stamp"

queue_count() {
  ssh -o BatchMode=yes "$host" \
    "curl -fsS --max-time 5 '$production_url/queue' | python3 -c 'import json,sys; d=json.load(sys.stdin); print(len(d.get(\"queue_running\", [])) + len(d.get(\"queue_pending\", [])))'"
}

echo "Waiting for the production ComfyUI queue to become idle..."
while [[ "$(queue_count)" != "0" ]]; do
  sleep 15
done

ssh -o BatchMode=yes "$host" "
  set -e
  test ! -e /mnt/disk2/models/minimax-h3-fastvideo/$model.aria2
  test -s /mnt/disk2/models/minimax-h3-fastvideo/$model
  test -s /mnt/disk2/models/minimax-h3-fastvideo/$video_vae
  mkdir -p '$remote_log_dir' /home/neo/comfyui-h3-veda-039/ComfyUI/models/vae
  ln -sfn /mnt/disk2/models/minimax-h3-fastvideo/$video_vae /home/neo/comfyui-h3-veda-039/ComfyUI/models/vae/$video_vae
  curl -fsS -X POST -H 'Content-Type: application/json' -d '{\"unload_models\":true,\"free_memory\":true}' '$production_url/free' >/dev/null
  systemctl --user restart comfyui-veda.service
"

for _ in $(seq 1 60); do
  if ssh -o BatchMode=yes "$host" "curl -fsS --max-time 3 '$shadow_url/system_stats' >/dev/null"; then
    break
  fi
  sleep 2
done

if [[ "$(queue_count)" != "0" ]]; then
  echo "Production received a new job; aborting the shadow benchmark." >&2
  exit 3
fi

ssh -o BatchMode=yes "$host" "
  curl -fsS --max-time 10 '$shadow_url/object_info/UNETLoader' | grep -q '$model'
  curl -fsS --max-time 10 '$shadow_url/object_info/VAELoader' | grep -q '$video_vae'
  nohup nvidia-smi --query-gpu=timestamp,index,utilization.gpu,memory.used,power.draw,clocks.sm --format=csv,noheader,nounits -l 5 > '$remote_log_dir/gpu.csv' 2>&1 </dev/null &
  echo \$! > '$remote_log_dir/gpu-monitor.pid'
"

cleanup() {
  ssh -o BatchMode=yes "$host" "
    if test -f '$remote_log_dir/gpu-monitor.pid'; then
      kill \$(cat '$remote_log_dir/gpu-monitor.pid') 2>/dev/null || true
    fi
  " >/dev/null 2>&1 || true
}
trap cleanup EXIT

run_case() {
  local label="$1"
  ssh -o BatchMode=yes "$host" "
    /usr/bin/time -f 'wall=%e max_rss_kb=%M' -o '$remote_log_dir/$label.time' \
      '$shadow_root/env-cu130/bin/python' '$shadow_root/run_fastvideo_benchmark.py' \
      --url '$shadow_url' \
      --width 832 --height 480 --duration 5 \
      --model '$model' \
      --video-vae '$video_vae' \
      --attention 'comfy kitchen attention' \
      --prefix 'h3-shadow/fasth3-trim-$stamp-$label' \
      > '$remote_log_dir/$label.jsonl' 2>&1
  "
  ssh -o BatchMode=yes "$host" "cat '$remote_log_dir/$label.time'; tail -n 1 '$remote_log_dir/$label.jsonl'"
}

echo "Running cold case..."
run_case cold

if [[ "$(queue_count)" != "0" ]]; then
  echo "Production received a new job; skipping the warm case." >&2
  exit 4
fi

echo "Running warm case..."
run_case warm

echo "RESULT_DIR=$remote_log_dir"
