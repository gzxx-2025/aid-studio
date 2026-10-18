---
name: aid-h3-3090-operations
description: Operate, benchmark, and capacity-plan the validated AID Studio MiniMax H3 deployment on the dual RTX 3090 host. Use for 10.190.0.222 H3 profile selection, warm/cold timing, 768P or FastH3 throughput estimates, reference-mode checks, controlled MultiStream or sparse-attention experiments, and rollback decisions; do not use it as a general H3 prompting guide.
---

# AID H3 3090 Operations

Treat the deployed production baseline as evidence, not as a template to rewrite speculatively. Read [references/validated-baseline.md](references/validated-baseline.md) before answering capacity questions, changing H3 routing, or comparing a new accelerator.

## Operational boundary

- Use the `gpu-resource-scheduler` skill and the central scheduler for every normal GPU workload. Read `/v1/profiles` and `/v1/snapshot` first.
- Never call the production ComfyUI ports, GPU devices, service manager, or SSH path as a workload shortcut. Scheduler unavailability is a hard failure.
- Administrative deployment or incident inspection is separate from generation. It requires an explicit request naming the target and must preserve rollback.
- Keep production and experiment profiles separate. Do not edit the validated production workflow in place to test a node, checkpoint, attention backend, quantization, or ComfyUI build.
- A submitted scheduler job is only queued until its state is `RUNNING`. Report the job ID, profile, assigned GPU, queue position, warm hit, and cold-start cost from scheduler facts.

## Choose the validated production path

- Use `h3-standard-quality` on GPU0 for 768P, reference images, first/last frames, Standard, or Turbo. The validated 768P ceiling is 5 seconds per segment.
- Use `h3-single-fast` on GPU1 for 480P text-to-video previews. It is not a reference-image or 768P path.
- Do not select Veda for production. Its retained profile is disabled because it consumed almost all VRAM without beating FastH3 at the validated 480P workload.
- For repeated T2V or multi-reference variants with an identical conditioning tuple, the isolated phase cache is a validated opt-in accelerator. Never reuse it across changed prompts, ordered reference hashes, dimensions, frames, model/encoder/VAE/LoRA identities, or conditioning parameters.
- Do not claim that one 768P render uses both GPUs. The current split allows one standard-quality job and one FastH3 preview service to coexist; GPU1 contributes no 768P output.
- Treat Agnes as an external primary video provider only when the AID routing configuration selects it. Local H3 remains a separately measured fallback; do not merge Agnes queue time with H3 runtime.

## Capacity calculations

Use completed `workerTotalSeconds`, not UI progress estimates:

`gross output seconds/day = 86400 / workerTotalSeconds * clip duration`

For a daily cold start followed by warm jobs, subtract the measured cold penalty once. Report both gross generated seconds and a practical range that reserves time for failures, switching, and retries. Do not count rejected-quality clips as accepted production output.

## Benchmarking or changing acceleration

Read [references/benchmark-protocol.md](references/benchmark-protocol.md) before testing MultiStream, Sol-Attn, VSA, caching, a new quant, or a new runtime. Establish a same-seed dense baseline first and change one independent variable at a time.
Use [references/acceleration-candidates.md](references/acceleration-candidates.md) to distinguish RTX 3090 candidates from newer-GPU-only research before downloading or deploying anything.

An acceleration can be recommended for production only when all of these are true:

1. The exact checkpoint/runtime combination completed cold and warm runs through an allow-listed scheduler profile.
2. Logs prove that the intended backend ran without fallback.
3. Reference inputs, audio, resolution, frames, steps, and output duration match the request.
4. Face, gaze, hands, motion, identity, prompt adherence, and temporal stability pass side-by-side review.
5. The result records end-to-end wall time, sampler time, VAE time, peak VRAM, RAM/swap, GPU utilization, and power.
6. Rollback restores the existing profile without requiring model re-download or production workflow repair.

Keep unverified candidates labeled `EXPERIMENTAL`. A faster sampler that increases rerenders is not a production speedup.

## Repository sources of truth

For implementation or incident work, inspect only the relevant files:

- `docs/gpu-resource-manager.md`
- `docs/gpu-scheduler-api-calling-guide.md`
- `docs/gpu-scheduler-project-migration-manual.md`
- `deploy/local/gpu_resource_manager.py`
- `deploy/local/gpu_resource_agent.py`
- `deploy/local/gpu_h3_quality_worker.py`
- `deploy/local/gpu_fasth3_worker.py`
- `deploy/local/gpu_h3_multistream_worker.py`
- `deploy/local/h3-shadow/run_multistream_benchmark.py`
- `deploy/local/h3-shadow/run_conditioning_cache_benchmark.py`
- `deploy/local/h3-shadow/run_reference_cache_benchmark.py`
- `deploy/local/h3-shadow/run_firstblock_cache_benchmark.py`
- `deploy/local/comfy_h3_adapter.py`
- `docs/benchmarks/h3-multistream-3090-2026-10-17.md`

Do not expose `.runtime/secrets/gpu_manager_api_key` or any administrator token in commands, output, logs, commits, or reports.
