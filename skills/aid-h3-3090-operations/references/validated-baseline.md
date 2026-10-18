# Validated H3 baseline on gpu-222

Last reconciled: 2026-10-17. Hardware: two RTX 3090 24 GiB cards on separate PCIe/NUMA paths without NVLink. AID reaches the scheduler through a loopback-only control-plane tunnel; worker ports remain private.

## Production layout

| GPU | Scheduler profile | Validated use | Warm policy |
|---|---|---|---|
| GPU0 | `h3-standard-quality` | Standard/Turbo, references, first/last frame, 480P and up to 5 s at 768P | 20 minutes |
| GPU1 | `h3-single-fast` | FastH3 VSA, 480P, 4–5 s, text-to-video preview | 6 hours |

`h3-veda-fast` and `h3-dual-dense` are not default production profiles. Veda is retained but disabled. The dual profile now has a validated fixed worker, but remains disabled by default because every job reserves both GPUs and removes the independent FastH3 preview lane.

`h3-dual-dense` now has an isolated, scheduler-controlled experimental worker. At 832×480, 107 frames and four standard-model steps, its warm dense run was 45.051 seconds versus 55.062 seconds on one GPU (18.2% end-to-end gain), with bit-exact decoded video and audio. This validates the mechanism, not default production enablement; the profile remains opt-in.

The 1344×768, five-second, 20-step validation then completed in 1035.687 seconds after a model/shape switch and 430.341 seconds warm. Both outputs had identical decoded video and audio hashes and passed the sampled-frame review. Compared with the earlier production single-GPU warm result of 1409.896 seconds, the isolated stack is 69.5% faster end-to-end; that figure includes MultiStream plus retained weights and text conditioning. Keep it opt-in because it occupies both GPUs.

At 832×480 and 20 steps, the exact-conditioning phase cache completed a
repeat seed in 164.297 seconds versus 502.737 seconds through the full graph, a
67.3% reduction (3.06×). Decoded video and audio hashes were identical.

The same mechanism was then validated on ordered multi-reference Ref2VA input
at 832×480, 107 frames and four Turbo-LoRA steps. A cached repeat completed
in 82.871 seconds versus 366.684 seconds through the full graph (77.4%
reduction, 4.42×), with bit-exact decoded video and audio. A separate run with
an adult man in a dark suit followed by an adult woman in a floral blouse
confirmed that both distinct identities, costumes and reference order reached
the generated scene. Cache reuse is approved only when every cache-key field,
including the ordered reference-file hashes, matches exactly.

The W6A8 DiT used 3.93 GiB less pinned weight cache and built that cache 26.6%
faster, but sampled 4.3% slower warm and materially changed the same-seed
output. INT8 ConvRot remains the quality default. The NVFP4/AWQ text encoder
was 42.2% smaller on disk/staging and reduced cold encoding 28.9%, but is not
numerically equivalent; it remains a multi-prompt/reference-review candidate.

## Completed scheduler evidence

| Job | Profile and mode | Output | End-to-end worker time |
|---|---|---|---:|
| 8 | Standard, cold | 5 s, 1344×768, text-to-video | 1931.030 s |
| 9 | Standard, warm | 5 s, 1344×768, text-to-video | 1409.896 s |
| 10 | Turbo, 2 references | 4 s, 480P | 777.039 s |
| 12 | FastH3 VSA, cold | 4 s, 480P | about 586.0 s client wall time |
| 13 | FastH3 VSA, warm | 4 s, 480P | about 46.9 s client wall time |
| 14 | Turbo, 2 references while FastH3 stayed warm on GPU1 | 4 s, 480P | 878.831 s |
| 31 | Dual dense INT8, phase-cache validation | 4.458 s, 832×480 | 164.297 s cached / 502.737 s full |
| 32 | Dual dense W6A8 comparison | 4.458 s, 832×480 | 170.261 s cached / 506.796 s full |
| 33 | Dual dense INT8 + NVFP4 encoder | 4.458 s, 832×480 | 166.326 s cached |
| 34 | FastH3 production restore, cold | 4 s, 480P | 675.767 s |
| 35 | Dual dense, 2-reference phase-cache validation | 4.458 s, 832×480 | 82.871 s cached / 366.684 s full |
| 36 | Dual dense, distinct-reference semantic check | 4.458 s, 832×480 | 62.396 s cached repeat after encode |
| 37 | Single dense, FirstBlockCache A/B, 20 steps | 4.458 s, 832×480 | 82.137 s Fast sample / 112.173 s baseline sample |
| 38 | FirstBlockCache warm confirmation | 4.458 s, 832×480 | 90.131 s Fast / 118.186 s Safe / 112.168 s baseline sample |
| 39 | FastH3 production restore after reference/cache tests, cold | 4 s, 480P | 804.864 s |

Job 11 is a retained failed validation and must not be used as a success sample.

## Capacity implications

- One warm standard GPU produces about `86400 / 1409.896 × 5 = 306` generated 768P seconds/day.
- Allowing one cold start per day gives about 60 five-second clips, or roughly 300 generated seconds/day.
- A practical production plan for the current split is 250–280 generated 768P seconds/day before editorial rejection.
- Reserving both GPUs for the validated experimental dual-dense stack gives about 1004 theoretical warm generated seconds/day, or a practical 850–900 seconds/day before editorial rejection. This is an alternative capacity mode, not additive to the current split.
- Historical pre-optimization 5-second 768P runs were about 31:52–38:50, averaging about 35:42.
- The earlier 500–600 seconds/day estimate assumed both 3090s independently ran validated standard 768P workers. The deployed split reserves GPU1 for FastH3, so its current 768P capacity is about half that estimate.

Do not turn the dual-card estimate into a current-state claim. It becomes valid only after a second standard worker or a bit-exact multi-GPU worker is validated and enabled.

## Known capability boundaries

- FastH3 does not accept production reference images in this deployment.
- Standard H3 768P is limited to 5 seconds per segment; longer requested shots must be segmented before generation.
- VSA-trained checkpoints must use their trained VSA pattern and keep ratio. Do not apply VSA semantics to an ordinary H3 checkpoint.
- Sparse attention can change motion and facial geometry. Earlier VSA review observed crossed-eye artifacts, so quality review must include gaze and eye alignment.
- Veda 480P cold/warm tests succeeded, but its throughput and residency were inferior to FastH3; keeping it warm has no current production benefit.
- The two 3090s have no NVLink. A multi-GPU design must benchmark host-staged and PCIe peer transfer rather than assuming pooled 48 GiB VRAM.
- The tested Sol-Attn arm was slower end-to-end and produced material ghosting. The tested FastH3 VSA arm was 11.8% faster than dual FastH3 dense but slower than the existing single-GPU FastH3 baseline and regressed prompt adherence. Neither is approved.
- Phase-cache reuse is a strict-key optimization, not a general model cache. A missing or stale cache dimension can silently bind the wrong prompt or references, so fail closed on cache metadata mismatch.
- Reference order is semantic input. Cache manifests must hash the ordered list, not a sorted set of files.
- FirstBlockCache Fast is a reproducible 20-step preview candidate, not a quality default. Jobs 37 and 38 reduced measured warm sampling by 26.8% and 19.6% while retaining both references; decoded outputs for each arm were identical between the two jobs. Fast SSIM/PSNR against dense baseline were 0.8923/26.11 dB. Safe was 5.4% slower than baseline on the warm confirmation and is rejected for throughput. Keep Fast isolated until a broader face/gaze/audio review is complete.
