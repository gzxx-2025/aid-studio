#!/usr/bin/env python3
"""Submit a reproducible FastVideo FastH3 benchmark to shadow ComfyUI."""

from __future__ import annotations

import argparse
import json
import time
import urllib.error
import urllib.request


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
    model = args.model
    text_encoder = args.text_encoder
    video_vae = args.video_vae
    audio_vae = "minimax_h3_audio_vae_fp32.safetensors"
    model_node = ["2", 0]
    prompt = {
        "1": {
            "class_type": "UNETLoader",
            "inputs": {"unet_name": model, "weight_dtype": "default"},
        },
        "2": {
            "class_type": "MiniMaxH3SigmaShift",
            "inputs": {
                "model": ["1", 0],
                "shift_video": args.shift_video,
                "shift_audio": args.shift_audio,
            },
        },
        "3": {
            "class_type": "ModelAttentionBackend",
            "inputs": {"model": model_node, "attention": args.attention},
        },
        "4": {
            "class_type": "BlockSparseAttention",
            "inputs": {
                "model": ["3", 0],
                "selection": "vsa",
                "selection.keep_percent": 10.0,
                "start_percent": 0.2,
                "end_percent": 1.0,
                "dense_blocks": "",
                "min_tokens": args.min_tokens,
                "extra_tokens": 256,
                "sink_conditioning": "exact_kv_and_rows",
                "verbose": True,
            },
        },
        "5": {
            "class_type": "CLIPLoader",
            "inputs": {"clip_name": text_encoder, "type": "minimax", "device": "default"},
        },
        "6": {"class_type": "VAELoader", "inputs": {"vae_name": video_vae}},
        "7": {"class_type": "VAELoader", "inputs": {"vae_name": audio_vae}},
        "8": {
            "class_type": "MiniMaxH3ImageToVideo",
            "inputs": {
                "clip": ["5", 0],
                "vae": ["6", 0],
                "prompt": args.prompt,
                "width": args.width,
                "height": args.height,
                "length": frame_count(args.duration),
            },
        },
        "9": {
            "class_type": "BasicGuider",
            "inputs": {"model": ["4", 0], "conditioning": ["8", 0]},
        },
        "10": {"class_type": "RandomNoise", "inputs": {"noise_seed": args.seed}},
        "11": {"class_type": "KSamplerSelect", "inputs": {"sampler_name": args.sampler}},
        "12": {
            "class_type": "BasicScheduler",
            "inputs": {
                "model": ["4", 0],
                "scheduler": args.scheduler,
                "steps": args.steps,
                "denoise": 1.0,
            },
        },
        "13": {
            "class_type": "SamplerCustomAdvanced",
            "inputs": {
                "noise": ["10", 0],
                "guider": ["9", 0],
                "sampler": ["11", 0],
                "sigmas": ["12", 0],
                "latent_image": ["8", 1],
            },
        },
        "14": {"class_type": "VAEDecode", "inputs": {"samples": ["13", 0], "vae": ["6", 0]}},
        "15": {
            "class_type": "VAEDecodeAudio",
            "inputs": {"samples": ["13", 0], "vae": ["7", 0]},
        },
        "16": {
            "class_type": "CreateVideo",
            "inputs": {
                "images": ["14", 0],
                "audio": ["15", 0],
                "fps": 24.0,
                "bit_depth": "auto",
                "color_space": "sRGB",
                "codec": "auto",
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
    if args.lora:
        prompt["18"] = {
            "class_type": "LoraLoaderModelOnly",
            "inputs": {
                "model": ["2", 0],
                "lora_name": args.lora,
                "strength_model": args.lora_strength,
            },
        }
        prompt["3"]["inputs"]["model"] = ["18", 0]
    if args.disable_sparse:
        prompt["9"]["inputs"]["model"] = ["3", 0]
        prompt["12"]["inputs"]["model"] = ["3", 0]
    return prompt


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--url", default="http://127.0.0.1:8190")
    parser.add_argument("--width", type=int, default=736)
    parser.add_argument("--height", type=int, default=416)
    parser.add_argument("--duration", type=float, default=5.0)
    parser.add_argument("--seed", type=int, default=20261004)
    parser.add_argument("--attention", default="comfy kitchen attention")
    parser.add_argument("--steps", type=int, default=8)
    parser.add_argument("--sampler", default="res_multistep")
    parser.add_argument("--scheduler", default="simple")
    parser.add_argument("--shift-video", type=float, default=10.0)
    parser.add_argument("--shift-audio", type=float, default=3.0)
    parser.add_argument("--min-tokens", type=int, default=8192)
    parser.add_argument("--lora")
    parser.add_argument("--lora-strength", type=float, default=1.0)
    parser.add_argument("--disable-sparse", action="store_true")
    parser.add_argument(
        "--model",
        default="fastvideo_fasth3_8step_v2_pruned_int8_convrot.safetensors",
    )
    parser.add_argument(
        "--text-encoder",
        default="qwen3vl_32b_minimax_h3_int8_convrot.safetensors",
    )
    parser.add_argument("--video-vae", default="minimax_h3_video_vae_fp16.safetensors")
    parser.add_argument("--prefix", default="h3-shadow/fastvideo-fasth3")
    parser.add_argument("--poll-seconds", type=float, default=5.0)
    parser.add_argument("--timeout-seconds", type=float, default=3600.0)
    parser.add_argument(
        "--prompt",
        default=(
            "A photorealistic cinematic medium-wide shot inside a modest rural Chinese home. "
            "A 30-year-old Chinese man in a plain white shirt walks naturally from the wooden "
            "door toward a table, pauses, and turns toward the warm window light. The camera "
            "slowly tracks sideways; realistic skin, stable anatomy, coherent motion, no text. "
            "Audio: soft footsteps on a wooden floor, quiet room ambience, distant birds outside."
        ),
    )
    args = parser.parse_args()

    submitted = request_json(
        f"{args.url.rstrip('/')}/prompt",
        {"prompt": build_prompt(args), "client_id": "h3-shadow-benchmark"},
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
            status = result.get("status", {})
            if status.get("completed") is False:
                raise SystemExit(2)
            return
        time.sleep(args.poll_seconds)
    raise TimeoutError(f"Benchmark did not finish within {args.timeout_seconds}s")


if __name__ == "__main__":
    main()
