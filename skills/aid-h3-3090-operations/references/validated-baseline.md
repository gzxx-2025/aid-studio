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

## Completed scheduler evidence

| Job | Profile and mode | Output | End-to-end worker time |
|---|---|---|---:|
| 8 | Standard, cold | 5 s, 1344×768, text-to-video | 1931.030 s |
| 9 | Standard, warm | 5 s, 1344×768, text-to-video | 1409.896 s |
| 10 | Turbo, 2 references | 4 s, 480P | 777.039 s |
| 12 | FastH3 VSA, cold | 4 s, 480P | about 586.0 s client wall time |
| 13 | FastH3 VSA, warm | 4 s, 480P | about 46.9 s client wall time |
| 14 | Turbo, 2 references while FastH3 stayed warm on GPU1 | 4 s, 480P | 878.831 s |

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
