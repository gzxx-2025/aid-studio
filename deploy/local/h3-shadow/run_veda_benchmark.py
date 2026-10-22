#!/usr/bin/env python3
"""Run a reproducible MiniMax H3 Turbo benchmark with optional Veda."""

from __future__ import annotations

import argparse
import json
import time
import urllib.error
import urllib.request


PREDICTOR = "minimax_h3_t2va_veda_8nfe_600step_preview_fp8.safetensors"


def request_json(url: str, payload: dict | None = None) -> dict:
    data = None if payload is None else json.dumps(payload).encode("utf-8")
    request = urllib.request.Request(
        url,
        data=data,
        headers={"Content-Type": "application/json"},
    )
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            return json.load(response)
    except urllib.error.HTTPError as exc:
        detail = exc.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"HTTP {exc.code}: {detail}") from exc


def frame_count(duration: float) -> int:
    frames = max(5, round(duration * 24))
    return frames + (5 - frames % 17) % 17


def build_prompt(args: argparse.Namespace) -> dict:
    model_output = ["10", 0]
    graph = {
        "1": {
            "class_type": "UNETLoader",
            "inputs": {
                "unet_name": "minimax_h3_fl2va_pruned_int8_convrot.safetensors",
                "weight_dtype": "default",
            },
        },
        "2": {
            "class_type": "LoraLoaderModelOnly",
            "inputs": {
                "model": ["1", 0],
                "lora_name": "minimax_h3_fl2v_turbo_8step_v1.0_comfyui_bf16.safetensors",
                "strength_model": 1.0,
            },
        },
        "4": {
            "class_type": "CLIPLoader",
            "inputs": {
                "clip_name": "qwen3vl_32b_minimax_h3_int8_convrot.safetensors",
                "type": "minimax",
                "device": "default",
            },
        },
        "5": {
            "class_type": "VAELoader",
            "inputs": {"vae_name": "minimax_h3_video_vae_fp16.safetensors"},
        },
        "6": {
            "class_type": "VAELoader",
            "inputs": {"vae_name": "minimax_h3_audio_vae_fp32.safetensors"},
        },
        "7": {
            "class_type": "MiniMaxH3ImageToVideo",
            "inputs": {
                "clip": ["4", 0],
                "vae": ["5", 0],
                "prompt": args.prompt,
                "width": args.width,
                "height": args.height,
                "length": frame_count(args.duration),
            },
        },
        "8": {"class_type": "RandomNoise", "inputs": {"noise_seed": args.seed}},
        "9": {"class_type": "KSamplerSelect", "inputs": {"sampler_name": "euler"}},
        "13": {
            "class_type": "LTXVSeparateAVLatent",
            "inputs": {"av_latent": ["12", 0]},
        },
        "14": {
            "class_type": "VAEDecodeTiled",
            "inputs": {
                "samples": ["13", 0],
                "vae": ["5", 0],
                "tile_size": 256,
                "overlap": 64,
                "temporal_size": 16,
                "temporal_overlap": 4,
            },
        },
        "15": {
            "class_type": "VAEDecodeAudio",
            "inputs": {"samples": ["13", 1], "vae": ["6", 0]},
        },
        "16": {
            "class_type": "CreateVideo",
            "inputs": {
                "images": ["14", 0],
                "audio": ["15", 0],
                "fps": 24.0,
                "bit_depth": 8,
                "color_space": "sRGB",
                "codec": "h264",
            },
        },
        "17": {
            "class_type": "SaveVideo",
            "inputs": {
                "video": ["16", 0],
                "filename_prefix": args.prefix,
                "format": "mp4",
                "format.codec": "h264",
            },
        },
    }
    if args.veda:
        graph["3"] = {
            "class_type": "VedaSparseAttention",
            "inputs": {
                "model": ["10", 0],
                "predictor": PREDICTOR,
                "generated_sparsity": args.generated_sparsity,
                "reference_sparsity": args.reference_sparsity,
                "full_attention_layers": "",
                "full_attention_steps": "",
                "verbose": True,
            },
        }
        model_output = ["3", 0]
    graph["10"] = {
        "class_type": "MiniMaxH3SigmaShift",
        "inputs": {"model": ["2", 0], "shift_video": 6.0, "shift_audio": 3.0},
    }
    graph["11"] = {
        "class_type": "BasicGuider",
        "inputs": {"model": model_output, "conditioning": ["7", 0]},
    }
    graph["18"] = {
        "class_type": "BasicScheduler",
        "inputs": {
            "model": model_output,
            "scheduler": "simple",
            "steps": args.steps,
            "denoise": 1.0,
        },
    }
    graph["12"] = {
        "class_type": "SamplerCustomAdvanced",
        "inputs": {
            "noise": ["8", 0],
            "guider": ["11", 0],
            "sampler": ["9", 0],
            "sigmas": ["18", 0],
            "latent_image": ["7", 1],
        },
    }
    return graph


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--url", default="http://127.0.0.1:8192")
    parser.add_argument("--width", type=int, default=832)
    parser.add_argument("--height", type=int, default=480)
    parser.add_argument("--duration", type=float, default=6.0)
    parser.add_argument("--steps", type=int, default=8)
    parser.add_argument("--seed", type=int, default=20261007)
    parser.add_argument("--veda", action="store_true")
    parser.add_argument("--generated-sparsity", default="90%")
    parser.add_argument("--reference-sparsity", default="0%")
    parser.add_argument("--prefix", default="h3-shadow/veda-benchmark")
    parser.add_argument("--poll-seconds", type=float, default=5.0)
    parser.add_argument("--timeout-seconds", type=float, default=3600.0)
    parser.add_argument(
        "--prompt",
        default=(
            "A photorealistic cinematic medium-wide shot inside a modest rural Chinese home. "
            "A 30-year-old Chinese man in a plain white shirt walks naturally from the wooden "
            "door toward a table, pauses, and turns toward warm window light. The camera slowly "
            "tracks sideways; realistic skin, stable anatomy, coherent motion, no text. Audio: "
            "soft footsteps on a wooden floor, quiet room ambience, distant birds outside."
        ),
    )
    args = parser.parse_args()

    submitted = request_json(
        f"{args.url.rstrip('/')}/prompt",
        {"prompt": build_prompt(args), "client_id": "h3-veda-benchmark"},
    )
    prompt_id = submitted["prompt_id"]
    started = time.monotonic()
    print(json.dumps({"prompt_id": prompt_id, "submitted": submitted}, ensure_ascii=False))
    while time.monotonic() - started < args.timeout_seconds:
        history = request_json(f"{args.url.rstrip('/')}/history/{prompt_id}")
        if prompt_id in history:
            elapsed = time.monotonic() - started
            result = history[prompt_id]
            print(json.dumps({"elapsed_seconds": elapsed, "history": result}, ensure_ascii=False))
            if result.get("status", {}).get("completed") is False:
                raise SystemExit(2)
            return
        time.sleep(args.poll_seconds)
    raise TimeoutError(f"Benchmark did not finish within {args.timeout_seconds}s")


if __name__ == "__main__":
    main()
