# MiniMax H3 acceleration candidates for dual RTX 3090

Last reviewed: 2026-10-17. Treat every item below as experimental until it passes the fixed-seed protocol in `benchmark-protocol.md` on `gpu-222`.

## Test first

1. **MultiStream dense, host exchange** — splits H3 transformer blocks across the two cards without changing dense attention math. Use host exchange because the cards are on separate `SYS` PCIe/NUMA paths and have no NVLink. Start with `exchange_chunks=8`, DynamicVRAM `keep`, and weight cache enabled. Source: <https://github.com/martonsagi/Comfy-H3-MultiStream>.
2. **Sol-Attn on base H3** — training-free sparse attention and the preferred sparse mode in the official ComfyUI H3 guidance. Confirm the CUDA `sol_attn` kernel in logs; a successful install with runtime fallback is not acceleration. Preserve conditioning rows. Sources: <https://github.com/Comfy-Org/docs/blob/main/tutorials/video/minimax/minimax-h3.mdx> and <https://github.com/cicalooo/ComfyUI-SolAttn-Ampere>.
3. **FastH3 VSA only on its trained checkpoint** — use the checkpoint's documented gate/compression branch and keep percentage. Never apply VSA settings to an ordinary H3 checkpoint. Inspect eyes and gaze because an earlier local output showed crossed-eye distortion.
4. **FirstBlockCache** — dependency-free and likely Ampere-compatible, but approximate. The published RTX 5090 benchmark reports about 30% cache-only savings in Fast mode; no 3090 result is established for that implementation. Use Safe/Fast before Aggressive and audit audio as well as frames. Source: <https://github.com/duckyshell/ComfyUI-MiniMaxH3-FirstBlockCache>.
5. **Smaller native quantized components** — benchmark the existing W6A8 DiT and NVFP4/AWQ text encoder separately. The smaller text encoder is primarily a cold-start and RAM/VRAM staging candidate; it is not automatically faster on Ampere because NVFP4 compute is emulated below Blackwell. Source catalog: <https://github.com/MiniMax-AI/awesome-minimax-h3-integration>.
6. **Conditioning and latent phase cache** — locally validated at 832×480/20 steps. An exact T2V repeat completed in 164.297 seconds versus 502.737 seconds through the full graph (67.3% reduction), with bit-exact decoded video and audio. Keep it opt-in and fail closed unless the full conditioning tuple matches. Reference-image reuse remains unvalidated. Source: <https://github.com/pepikir/minimax-h3-speedup>.

## Secondary candidates

- **Spark-H3 Sol-Attn kernel** has an SM86 path explicitly validated on RTX 3090. It is a promising kernel comparison after the built-in Comfy Kitchen Sol-Attn path is measured, not a reason to replace the working runtime first. Source: <https://github.com/zechengtang/Spark-H3/blob/main/h3_sparse_attention/README.md>.
- **SGLang Diffusion** exposes H3 quantization, Ulysses parallelism and several sparse backends. Its `kitchen_int8` single-24-GB recipe may be relevant, but its high-performance SubBlock/VSA recipes target newer compute capabilities or datacenter topologies. Treat a runtime migration as a separate project. Source: <https://github.com/sgl-project/sglang/blob/main/docs/cookbook/diffusion/MiniMax/MiniMax-H3.mdx>.
- **GGUF** reduces component size and may avoid OOM/offload pressure, but dequantization and loader overhead can make it slower than native ConvRot/W6A8 on a 3090. Use it for capacity only after native quants are compared. Source: <https://github.com/kyuz0/ComfyUI-GGUF-H3>.
- **Raylight/xDiT sequence parallelism** reports stronger 2×/4× RTX 3090 sampling scaling than host-exchange MultiStream, plus FSDP for 15-second clips. It is a separate runtime and deployment stack, so compare it in an isolated third round instead of changing the validated ComfyUI service. Source: <https://github.com/alesha-pro/tools/blob/main/minimax-h3/workflows/multi-gpu/README.md>.
- **Consecutive FirstBlockCache / TeaCache-style block reuse** has published H3 gains around 8–10% at 20 steps and larger gains at 40 steps, but it is approximate and must be tested across scenes, seeds, faces, gaze and audio. Source: <https://github.com/loading-awesome/MiniMax-H3-Swift/blob/main/docs/PERFORMANCE_GUIDE.md>.
- **Integrated alternative H3 stacks** combine Spectrum/FirstBlockCache/EasyCache, audio-safe SLA, TensorRT decode, NVENC and low-step adapters. Treat these as a matrix of independent candidates rather than deploying the entire stack at once. Source: <https://github.com/keboqi/minimax-h3>.

## Do not deploy from headline numbers alone

- MultiStream reference gains come from different GPUs and topology; this host crosses `SYS`, so PCIe/NUMA exchange may erase part of the gain.
- Sparse attention and caches change the numerical trajectory. Measure accepted-clip throughput, not only seconds per step.
- A low-step or distilled checkpoint is a different quality operating point, not a lossless replacement for standard 20-step H3.
- Veda already failed the local residency/throughput value test at 480P and remains disabled.
- VC-Attention and other recent low-bit attention work report promising H3 results, but currently published target implementations do not establish an RTX 3090 production path.
- MC-Sparse reports a 1.80× H3 denoising speedup with small quality loss in research results, but no validated ComfyUI/SM86 production integration is available yet. Track it; do not deploy it from the paper result alone. Source: <https://arxiv.org/abs/2610.06801>.

## Local second-round decision

- MultiStream dense at 480P: accepted as lossless opt-in; 18.2% warm end-to-end improvement and bit-exact decoded media. At 768P the full isolated stack completed in 430.341 seconds warm versus the earlier 1409.896-second production baseline; that larger gain also includes retained weights and text conditioning.
- Sol-Attn (`tau=1.3`, start `0.2`) at 480P: rejected; slower total time plus severe ghosting/duplication.
- FastH3 VSA at 480P: rejected as a default; 11.8% faster than dual FastH3 dense, but slower than single-GPU FastH3 production and it introduced unwanted text/composition changes.
- Conditioning/latent caching is accepted for strict-key T2V repeats. W6A8 is rejected as the quality default because it was slower warm and materially changed output. NVFP4/AWQ text encoding remains a cold-start candidate after a 28.9% encode reduction and 42.2% smaller staged model, pending broader quality and reference tests.
- Raylight/xDiT is the next isolated multi-GPU runtime comparison. Do not assume its published 4×3090 result transfers to this 2×3090 `SYS` topology: another MultiStream benchmark found xDiT slower on its tested system. Source: <https://github.com/martonsagi/Comfy-H3-MultiStream/blob/master/docs/performance.md>.
