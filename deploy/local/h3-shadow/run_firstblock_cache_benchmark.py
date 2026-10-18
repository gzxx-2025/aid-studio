#!/usr/bin/env python3
"""Benchmark MiniMax H3 FirstBlockCache on an ordered multi-reference workflow."""

from __future__ import annotations

import argparse
import json
import time
import uuid

import run_conditioning_cache_benchmark as common
import run_reference_cache_benchmark as reference


CACHE_MODES = {
    "baseline": "Off",
    "safe": "H3 Safe — 0.08 / max 2",
    "fast": "H3 Fast — 0.10 / max 2",
}


def model_graph(cache_mode: str) -> tuple[dict, list[object]]:
    graph = {
        "1": {
            "class_type": "UNETLoader",
            "inputs": {"unet_name": reference.REF_MODEL, "weight_dtype": "default"},
        },
        "2": {
            "class_type": "MiniMaxH3SigmaShift",
            "inputs": {"model": ["1", 0], "shift_video": 12.0, "shift_audio": 3.0},
        },
        "3": {
            "class_type": "ModelAttentionBackend",
            "inputs": {"model": ["2", 0], "attention": "comfy kitchen attention"},
        },
        "4": {
            "class_type": "ApplyMiniMaxH3FirstBlockCache",
            "inputs": {
                "model": ["3", 0],
                "mode": CACHE_MODES[cache_mode],
                "threshold": 0.10,
                "start_percent": 0.10,
                "end_percent": 0.95,
                "max_consecutive_hits": 2,
                "temporal_guard": False,
            },
        },
        "5": {"class_type": "H3BenchmarkForceRun", "inputs": {"model": ["4", 0]}},
    }
    return graph, ["5", 0]


def sample_graph(
    args: argparse.Namespace,
    cache_key: str,
    seed: int,
    cache_mode: str,
    output_key: str,
) -> dict:
    graph, model = model_graph(cache_mode)
    graph.update({
        "6": {
            "class_type": "H3LoadConditioning",
            "inputs": {"file_name": f"{cache_key}.cond.pt"},
        },
        "7": {
            "class_type": "H3LoadLatentAV",
            "inputs": {"file_name": f"{cache_key}-input.lat.pt"},
        },
        "8": {"class_type": "BasicGuider", "inputs": {"model": model, "conditioning": ["6", 0]}},
        "9": {"class_type": "RandomNoise", "inputs": {"noise_seed": seed}},
        "10": {"class_type": "KSamplerSelect", "inputs": {"sampler_name": "res_multistep"}},
        "11": {
            "class_type": "BasicScheduler",
            "inputs": {"model": model, "scheduler": "simple", "steps": 20, "denoise": 1.0},
        },
        "12": {
            "class_type": "SamplerCustomAdvanced",
            "inputs": {
                "noise": ["9", 0], "guider": ["8", 0], "sampler": ["10", 0],
                "sigmas": ["11", 0], "latent_image": ["7", 0],
            },
        },
        "13": {
            "class_type": "H3SaveLatentAV",
            "inputs": {"samples": ["12", 0], "filename": output_key},
        },
    })
    return graph


def decode_graph(args: argparse.Namespace, output_key: str, prefix: str) -> dict:
    graph = common.decode_graph(args, output_key, 0, prefix)
    graph["1"]["inputs"]["file_name"] = f"{output_key}.lat.pt"
    return graph


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--url", default="http://127.0.0.1:8193")
    parser.add_argument("--reference-image", dest="reference_images", action="append", required=True)
    parser.add_argument("--width", type=int, default=832)
    parser.add_argument("--height", type=int, default=480)
    parser.add_argument("--duration", type=float, choices=(4.0, 5.0), default=4.0)
    parser.add_argument("--seed", type=int, default=20261017)
    parser.add_argument("--cache-key", default="")
    parser.add_argument("--prefix", default="h3-firstblock-cache/benchmark")
    parser.add_argument("--timeout-seconds", type=int, default=7200)
    parser.add_argument("--prompt", default=reference.DEFAULT_PROMPT)
    args = parser.parse_args()
    if (args.width, args.height) != (832, 480):
        raise SystemExit("FirstBlockCache validation is initially restricted to 832x480")
    if not 2 <= len(args.reference_images) <= 3:
        raise SystemExit("FirstBlockCache validation requires two or three ordered images")

    cache_key = args.cache_key or f"firstblock-{int(time.time())}-{uuid.uuid4().hex[:8]}"
    stages: list[dict] = []
    stages.append({"stage": "encode-references", **common.run_graph(
        args.url, reference.encode_graph(args, cache_key), args.timeout_seconds,
        "h3-firstblock-encode",
    )})

    warmup_key = f"{cache_key}-warmup"
    stages.append({"stage": "sample-warmup", **common.run_graph(
        args.url,
        sample_graph(args, cache_key, args.seed, "baseline", warmup_key),
        args.timeout_seconds,
        "h3-firstblock-warmup",
    )})

    for mode in ("baseline", "safe", "fast"):
        output_key = f"{cache_key}-{mode}"
        stages.append({"stage": f"sample-{mode}", **common.run_graph(
            args.url,
            sample_graph(args, cache_key, args.seed, mode, output_key),
            args.timeout_seconds,
            f"h3-firstblock-sample-{mode}",
        )})
        stages.append({"stage": f"decode-{mode}", **common.run_graph(
            args.url,
            decode_graph(args, output_key, f"{args.prefix}/{mode}"),
            args.timeout_seconds,
            f"h3-firstblock-decode-{mode}",
        )})

    print(json.dumps({
        "cacheKey": cache_key,
        "inputMode": "multi-reference-image",
        "referenceImages": args.reference_images,
        "width": args.width,
        "height": args.height,
        "frames": common.frame_count(args.duration),
        "steps": 20,
        "sampler": "res_multistep",
        "seed": args.seed,
        "cacheModes": CACHE_MODES,
        "stages": stages,
    }, ensure_ascii=False, separators=(",", ":")))


if __name__ == "__main__":
    main()
