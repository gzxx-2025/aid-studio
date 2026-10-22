#!/usr/bin/env bash
set -euo pipefail

shadow_root=/home/neo/comfyui-fasth3-shadow
shadow_port="${H3_SHADOW_PORT:-8190}"
shadow_env="${H3_SHADOW_ENV:-env}"

exec "$shadow_root/$shadow_env/bin/python" "$shadow_root/ComfyUI/main.py" \
  --listen 0.0.0.0 \
  --port "$shadow_port" \
  --cuda-device 0 \
  --disable-async-offload \
  --reserve-vram 2 \
  --extra-model-paths-config "$shadow_root/extra_model_paths.yaml"
