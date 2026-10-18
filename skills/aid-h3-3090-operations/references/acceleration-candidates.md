# MiniMax H3 acceleration candidates for dual RTX 3090

Last reviewed: 2026-10-17. Treat every item below as experimental until it passes the fixed-seed protocol in `benchmark-protocol.md` on `gpu-222`.

## Test first

1. **MultiStream dense, host exchange** — splits H3 transformer blocks across the two cards without changing dense attention math. Use host exchange because the cards are on separate `SYS` PCIe/NUMA paths and have no NVLink. Start with `exchange_chunks=8`, DynamicVRAM `keep`, and weight cache enabled. Source: <https://github.com/martonsagi/Comfy-H3-MultiStream>.
2. **Sol-Attn on base H3** — training-free sparse attention and the preferred sparse mode in the official ComfyUI H3 guidance. Confirm the CUDA `sol_attn` kernel in logs; a successful install with runtime fallback is not acceleration. Preserve conditioning rows. Sources: <https://github.com/Comfy-Org/docs/blob/main/tutorials/video/minimax/minimax-h3.mdx> and <https://github.com/cicalooo/ComfyUI-SolAttn-Ampere>.
3. **FastH3 VSA only on its trained checkpoint** — use the checkpoint's documented gate/compression branch and keep percentage. Never apply VSA settings to an ordinary H3 checkpoint. Inspect eyes and gaze because an earlier local output showed crossed-eye distortion.
4. **FirstBlockCache** — dependency-free and Ampere-compatible, but approximate. The local 3090 Fast arm cached 9/20 steps and reduced measured sampling by 26.8% on Job 37 and 19.6% on the warm confirmation. Both ordered references remained recognizable; SSIM/PSNR versus baseline were 0.8923/26.11 dB, and decoded outputs reproduced exactly across the two jobs. Safe was slower than baseline in the warm confirmation. Keep Fast experimental pending a broader face/gaze/audio set; reject Safe for throughput. Source: <https://github.com/duckyshell/ComfyUI-MiniMaxH3-FirstBlockCache>.
5. **Smaller native quantized components** — benchmark the existing W6A8 DiT and NVFP4/AWQ text encoder separately. The smaller text encoder is primarily a cold-start and RAM/VRAM staging candidate; it is not automatically faster on Ampere because NVFP4 compute is emulated below Blackwell. Source catalog: <https://github.com/MiniMax-AI/awesome-minimax-h3-integration>.
6. **Conditioning and latent phase cache** — locally validated for both T2V and ordered multi-reference Ref2VA input. The reference repeat completed in 82.871 seconds versus 366.684 seconds through the full graph (4.42×), with bit-exact decoded video and audio. A distinct man/woman reference run also preserved both identities, costumes and order. Keep it opt-in and fail closed unless the full conditioning tuple and ordered reference hashes match. Source: <https://github.com/pepikir/minimax-h3-speedup>.

## Secondary candidates

- **Spark-H3 Sol-Attn kernel** has an SM86 path explicitly validated on RTX 3090. It is a promising kernel comparison after the built-in Comfy Kitchen Sol-Attn path is measured, not a reason to replace the working runtime first. Source: <https://github.com/zechengtang/Spark-H3/blob/main/h3_sparse_attention/README.md>.
- **SGLang Diffusion** exposes H3 quantization, Ulysses parallelism and several sparse backends. Its `kitchen_int8` single-24-GB recipe may be relevant, but its high-performance SubBlock/VSA recipes target newer compute capabilities or datacenter topologies. Treat a runtime migration as a separate project. Source: <https://github.com/sgl-project/sglang/blob/main/docs/cookbook/diffusion/MiniMax/MiniMax-H3.mdx>.
- **GGUF** reduces component size and may avoid OOM/offload pressure, but dequantization and loader overhead can make it slower than native ConvRot/W6A8 on a 3090. Use it for capacity only after native quants are compared. Source: <https://github.com/kyuz0/ComfyUI-GGUF-H3>.
- **Raylight/xDiT sequence parallelism** publishes a two-RTX-3090 INT8 result of 36.0 seconds/step at 1344×768 and needs a separate PyTorch 2.8 runtime; longer 362-frame cases also require FSDP. The validated local MultiStream stack is already about 6.6 seconds/step warm at 1344×768, so Raylight is rejected for deployment on this host unless a materially faster upstream result appears. Source: <https://github.com/alesha-pro/tools/blob/main/minimax-h3/workflows/multi-gpu/README.md>.
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
- Conditioning/latent caching is accepted for strict-key T2V and ordered multi-reference repeats. W6A8 is rejected as the quality default because it was slower warm and materially changed output. NVFP4/AWQ text encoding remains a cold-start candidate after a 28.9% encode reduction and 42.2% smaller staged model, pending broader quality and reference tests.
- FirstBlockCache Fast is a provisional 20-step preview candidate after reproducible 19.6–26.8% sample-time reductions. Safe is rejected because its warm confirmation was 5.4% slower than dense baseline. Do not combine Fast with Turbo/low-step LoRAs or another block cache, and do not make it the final-quality default without a multi-prompt editorial pass.
- Raylight/xDiT is not a current deployment candidate: its published two-3090 1344×768 result is much slower than the validated local MultiStream stack and requires a separate runtime.
- PDD 8-step remains an opt-in 768P preview candidate after a 305.237-second warm run, 29.1% faster than the accepted 430.341-second dense baseline. Do not use it for final output until the multi-prompt face, gaze, hands, reference-identity and audio set passes.
- SageAttention is rejected for this stack: although its CUDA path was confirmed in logs, the 470.459-second warm result was 9.3% slower than dense and changed same-seed composition.
