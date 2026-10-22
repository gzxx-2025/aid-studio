# H3 acceleration benchmark protocol

Use this protocol for MultiStream, sparse attention, cache, quantization, or runtime comparisons. Keep production traffic out of the experiment window.

Do not rely on unique node IDs to force a new ComfyUI execution. They may still return a cached output. Use a dedicated `IS_CHANGED` benchmark node downstream of reusable loaders, and reject any run that reuses the previous filename or completes without sampler log entries. When a common cross-runtime graph cannot include that node, use a fixed per-pass seed schedule (for example cold seed A, warm-1 seed B, warm-2 seed C) and use the same schedule in every comparison arm; compare matching seeds across arms. Reject any implausibly short warm run as a cache hit. `--cache-none` forces execution but also defeats model residency, so it cannot establish a warm baseline.

## Fixed comparison inputs

Persist the exact prompt, negative prompt, seed, reference files and hashes, checkpoint hash, LoRA hash, workflow hash, ComfyUI commit, custom-node commits, PyTorch/CUDA versions, dimensions, frame count, FPS, sampler, scheduler, steps, CFG, shift, denoise, VAE mode, and audio setting.

Minimum workloads:

1. 608×352 or the smallest supported smoke test, 39 frames.
2. 832×480, 124 frames, text-to-video.
3. 1344×768, 124 frames, text-to-video.
4. 1344×768, 124 frames, the production reference workload when the candidate supports references.

Use the same input for every arm. Run a cold pass and at least two warm passes. Do not mix model-load time into only one comparison arm.

## Test order

1. Dense single-GPU production baseline.
2. MultiStream dense with host exchange and no sparse approximation.
3. MultiStream dense with exchange chunking; start with 8 only after the serial run succeeds.
4. Sol-Attn on the ordinary/base checkpoint, with conditioning/reference rows protected.
5. VSA only on a checkpoint containing the trained gate/coarse branch, at its documented keep percentage.
6. Combined MultiStream plus the winning sparse mode.
7. Optional text-encoder output cache, pinned weight cache, VAE split decode, or first-block cache as separate variables.
8. Quantized checkpoint candidates last, because they change memory, compatibility, and possibly output quality simultaneously.

For a conditioning/latent cache arm, persist and validate a cache manifest. Its
key must cover the prompt and negative prompt, reference file hashes and order,
dimensions, frame count, checkpoint and text-encoder hashes, VAE, LoRA,
conditioning parameters and runtime schema. Treat a cache miss or metadata
mismatch as a hard miss. Never load cache files supplied by an untrusted user.

For FirstBlockCache or another approximate block-reuse arm, warm its compiler
path before recording timings. Compare sampler time separately from decode and
end-to-end time, because the first cache run and first VAE decode can each carry
one-time overhead. Never combine a block cache with a low-step/Turbo adapter in
the same acceptance test. Record cached-step indices, then review identity,
costume, hands, gaze, motion and audio against the dense baseline.

## Measurements

Record for each run:

- queue wait, runtime readiness, model load, text encoder, diffusion total, seconds/step, VAE decode, audio, mux, and total wall time;
- GPU utilization, VRAM, power and clocks for each GPU every few seconds;
- host CPU, RAM, swap and PCIe traffic;
- scheduler job/profile, warm hit, assigned GPUs, terminal status and error;
- output frame count, duration, resolution, audio presence and file validity.

Compute throughput from total wall time, not diffusion-only time.

## Quality gates

Compare dense and candidate outputs side by side at the same seed. Review at least the first, quarter, middle, three-quarter and last frames, plus motion playback. Score:

- identity and costume;
- face shape, eye direction/alignment and mouth;
- hands, limbs and object contacts;
- action order and prompt adherence;
- camera path, motion smoothness and temporal flicker;
- scene/reference consistency;
- audio synchronization and corruption.

MultiStream dense should be bit-exact or explainably deterministic relative to its single-GPU baseline. Sparse and cache methods may change the trajectory; they still require an editorial pass-rate comparison across multiple prompts.

For bit-exact checks, hash decoded video frames and decoded audio frames. Container hashes are not sufficient because mux metadata can differ.

## Stop and rollback conditions

Stop the arm after one repeatable OOM, silent dense fallback, malformed media, missing reference, corrupt audio, unresolved process leak, or material face/identity regression. Do not retry the same unchanged failure more than once.

The experiment must use a dedicated workflow copy, service name, private port, output prefix, and scheduler profile. Rollback disables that profile, drains or cancels queued jobs, releases workers/caches, and verifies the production profiles and their health before declaring completion.
