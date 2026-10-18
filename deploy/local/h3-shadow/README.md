# H3 shadow benchmark

This directory contains the reversible FastH3 benchmark harness for
`10.190.0.222`. It does not replace the production ComfyUI service on port
`8188`.

Start the CUDA 12.8 control environment:

```bash
H3_SHADOW_PORT=8190 H3_SHADOW_ENV=env ./start-shadow-comfy.sh
```

Start the CUDA 13.0/VSA environment:

```bash
H3_SHADOW_PORT=8191 H3_SHADOW_ENV=env-cu130 ./start-shadow-comfy.sh
```

For the AID Studio FAST route, install `comfyui-fasth3.service` as a lingering
user unit. This keeps port `8191` alive across logouts and host restarts. The
adapter keeps a completed FastH3 graph warm for six hours by default and
cancels the pending release when another FastH3 request arrives.

On the dual-3090 production node, the standard H3/Turbo/reference worker is
fixed to GPU0 and the FastH3/VSA worker is fixed to GPU1. Keep the scheduler
profile and the service `--cuda-device` setting aligned with that split.

The 736x416 profile uses `min_tokens=8192`; logs must show a sparse producer
path rather than `tokens < min_tokens` before the profile is considered active.

Submit the reproducible 10-second benchmark:

```bash
./env-cu130/bin/python ./run_fastvideo_benchmark.py \
  --url http://127.0.0.1:8191 \
  --width 832 --height 480 --duration 10 \
  --attention "comfy kitchen attention"
```

FastH3 V2 is T2VA-only. Do not route first/last-frame or multi-reference
production requests to this workflow; keep those on the base H3 FL2VA/Ref2VA
path.

## Veda / ComfyUI 0.39 shadow

The reversible Veda test instance lives at
`/home/neo/comfyui-h3-veda-039` on `10.190.0.222` and listens on port `8192`.
It is separate from production port `8188` and the existing FastH3 shadow on
port `8191`.

Start or stop it on the GPU server:

```bash
./veda-shadow.sh start
./veda-shadow.sh status
./veda-shadow.sh stop
```

For the production FAST route, install `comfyui-veda.service` as a lingering
user unit. This keeps the CUDA 13 worker isolated from production port 8188
while allowing automatic restart after a host reboot.

Run the safe 832x480 control and Veda cases with the same seed:

```bash
./env-cu130/bin/python ./run_veda_benchmark.py --url http://127.0.0.1:8192
./env-cu130/bin/python ./run_veda_benchmark.py --url http://127.0.0.1:8192 --veda
```

Do not start a benchmark while production port `8188` has a running or pending
job. The two instances share GPU 0 and a simultaneous H3 run can invalidate
timings or cause an out-of-memory failure.

## Conditioning and latent phase cache

The isolated dual-GPU lab on port `8193` also contains the pinned MIT-licensed
cache nodes from `pepikir/minimax-h3-speedup` commit
`4081f500ed733eebcdb5853cb0a75b9aa5e14c9f`. The benchmark splits a render into
text conditioning, diffusion/latent save, and VAE decode phases:

```bash
./env-cu130/bin/python ./run_conditioning_cache_benchmark.py \
  --url http://127.0.0.1:8193 --mode dual-dense \
  --dit int8 --text-encoder int8 \
  --width 832 --height 480 --duration 4 --steps 20
```

Use this only for trusted local cache files. A cache key must change whenever
the prompt, ordered reference hashes, dimensions, frame count, checkpoint,
text encoder, VAE, LoRA, or conditioning parameters change.

Ordered multi-reference reuse is validated with:

```bash
./env-cu130/bin/python ./run_reference_cache_benchmark.py \
  --url http://127.0.0.1:8193 --mode dual-dense \
  --width 832 --height 480 --duration 4 \
  --reference-image person-a.png --reference-image person-b.png
```

At 832×480 the cached repeat was 4.42× faster than the full graph and its
decoded video/audio hashes matched exactly. A separate distinct-person run
confirmed both reference identities, costumes and order. Do not sort the
reference list when computing the cache key.

## FirstBlockCache experiment

The isolated port-8193 lab can load the pinned MIT-licensed
`ComfyUI-MiniMaxH3-FirstBlockCache` node at commit
`d474755360cc1784df1b7c933ca28195e55e3445`. It must not be installed into
the production ComfyUI instance. Run the fixed 20-step reference A/B with:

```bash
./env-cu130/bin/python ./run_firstblock_cache_benchmark.py \
  --url http://127.0.0.1:8193 --width 832 --height 480 --duration 4 \
  --reference-image person-a.png --reference-image person-b.png
```

The benchmark warms the dense graph, then records dense, Safe and Fast arms
with the same seed. Do not combine FirstBlockCache with Turbo/low-step LoRAs or
another block cache. Treat it as approximate and review faces, gaze, costumes,
motion and audio before considering any production route.
