#!/usr/bin/env python3
"""Benchmark lossless MiniMax H3 conditioning/latent phase caching."""

from __future__ import annotations

import argparse
import json
import time
import urllib.error
import urllib.request
import uuid


MODELS = {
    "int8": "minimax_h3_fl2va_pruned_int8_convrot.safetensors",
    "w6a8": "minimax_h3_fl2va_pruned_w6a8.safetensors",
}
TEXT_ENCODERS = {
    "int8": "qwen3vl_32b_minimax_h3_int8_convrot.safetensors",
    "nvfp4": "qwen3vl_32b_heretic_minimax_h3_nvfp4.safetensors",
}


def request_json(url: str, payload: dict | None = None) -> dict:
    data = None if payload is None else json.dumps(payload).encode("utf-8")
    request = urllib.request.Request(
        url, data=data, headers={"Content-Type": "application/json"}
    )
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            return json.load(response)
    except urllib.error.HTTPError as exc:
        detail = exc.read().decode("utf-8", "replace")[-4_000:]
        raise RuntimeError(f"HTTP {exc.code}: {detail}") from exc


def frame_count(duration: float) -> int:
    frames = max(5, round(duration * 24))
    return min(362, frames + (5 - frames % 17) % 17)


def unique_node_ids(prompt: dict) -> dict:
    prefix = uuid.uuid4().hex[:12] + "_"
    node_ids = {node_id: prefix + node_id for node_id in prompt}

    def remap(value: object) -> object:
        if (
            isinstance(value, list)
            and len(value) == 2
            and isinstance(value[0], str)
            and value[0] in node_ids
            and isinstance(value[1], int)
        ):
            return [node_ids[value[0]], value[1]]
        if isinstance(value, list):
            return [remap(item) for item in value]
        if isinstance(value, dict):
            return {key: remap(item) for key, item in value.items()}
        return value

    return {node_ids[node_id]: remap(node) for node_id, node in prompt.items()}


def output_ref(history: dict) -> dict | None:
    for value in history.get("outputs", {}).values():
        if not isinstance(value, dict):
            continue
        for key in ("videos", "gifs", "images"):
            items = value.get(key)
            if isinstance(items, list) and items:
                return items[0]
    return None


def run_graph(url: str, graph: dict, timeout_seconds: int, client_id: str) -> dict:
    started = time.monotonic()
    submitted = request_json(
        url.rstrip("/") + "/prompt",
        {"prompt": unique_node_ids(graph), "client_id": client_id},
    )
    prompt_id = submitted["prompt_id"]
    while time.monotonic() - started < timeout_seconds:
        history = request_json(f"{url.rstrip('/')}/history/{prompt_id}")
        if prompt_id in history:
            result = history[prompt_id]
            status = result.get("status") or {}
            if status.get("completed") is False:
                raise RuntimeError(json.dumps(status, ensure_ascii=False))
            return {
                "promptId": prompt_id,
                "elapsedSeconds": time.monotonic() - started,
                "output": output_ref(result),
            }
        time.sleep(2)
    raise TimeoutError(f"graph {client_id} exceeded its fixed timeout")


def model_graph(args: argparse.Namespace) -> tuple[dict, list[object]]:
    model_source: list[object] = ["3", 0]
    graph = {
        "1": {
            "class_type": "UNETLoader",
            "inputs": {"unet_name": MODELS[args.dit], "weight_dtype": "default"},
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
            "class_type": "H3MultiStream",
            "inputs": {
                "model": model_source,
                "enabled": args.mode == "dual-dense",
                "second_gpu": 1,
                "exchange": "host",
                "exchange_chunks": args.exchange_chunks,
                "sparse_attention": False,
                "dynamic_vram": "keep",
                "vram_block_cache": False,
                "vram_reserve_gb": 4.0,
                "sparse_vsa": False,
                "weight_cache": True,
                "cache_ram_reserve_gb": 24.0,
            },
        },
        "5": {"class_type": "H3BenchmarkForceRun", "inputs": {"model": ["4", 0]}},
    }
    return graph, ["5", 0]


def encode_graph(args: argparse.Namespace, cache_key: str) -> dict:
    return {
        "1": {
            "class_type": "CLIPLoader",
            "inputs": {
                "clip_name": TEXT_ENCODERS[args.text_encoder],
                "type": "minimax",
                "device": "default",
            },
        },
        "2": {
            "class_type": "VAELoader",
            "inputs": {"vae_name": "minimax_h3_video_vae_fp16.safetensors"},
        },
        "3": {
            "class_type": "MiniMaxH3ImageToVideo",
            "inputs": {
                "clip": ["1", 0],
                "vae": ["2", 0],
                "prompt": args.prompt,
                "width": args.width,
                "height": args.height,
                "length": frame_count(args.duration),
            },
        },
        "4": {
            "class_type": "H3SaveConditioning",
            "inputs": {"conditioning": ["3", 0], "filename": cache_key},
        },
    }


def sample_graph(args: argparse.Namespace, cache_key: str, seed: int) -> dict:
    graph, model = model_graph(args)
    graph.update(
        {
            "6": {
                "class_type": "H3LoadConditioning",
                "inputs": {"file_name": f"{cache_key}.cond.pt"},
            },
            "7": {
                "class_type": "EmptyMiniMaxH3LatentAV",
                "inputs": {
                    "width": args.width,
                    "height": args.height,
                    "length": frame_count(args.duration),
                },
            },
            "8": {
                "class_type": "BasicGuider",
                "inputs": {"model": model, "conditioning": ["6", 0]},
            },
            "9": {"class_type": "RandomNoise", "inputs": {"noise_seed": seed}},
            "10": {"class_type": "KSamplerSelect", "inputs": {"sampler_name": "res_multistep"}},
            "11": {
                "class_type": "BasicScheduler",
                "inputs": {"model": model, "scheduler": "simple", "steps": args.steps, "denoise": 1.0},
            },
            "12": {
                "class_type": "SamplerCustomAdvanced",
                "inputs": {
                    "noise": ["9", 0],
                    "guider": ["8", 0],
                    "sampler": ["10", 0],
                    "sigmas": ["11", 0],
                    "latent_image": ["7", 0],
                },
            },
            "13": {
                "class_type": "H3SaveLatentAV",
                "inputs": {"samples": ["12", 0], "filename": f"{cache_key}-{seed}"},
            },
        }
    )
    return graph


def decode_graph(args: argparse.Namespace, cache_key: str, seed: int, prefix: str) -> dict:
    return {
        "1": {
            "class_type": "H3LoadLatentAV",
            "inputs": {"file_name": f"{cache_key}-{seed}.lat.pt"},
        },
        "2": {
            "class_type": "VAELoader",
            "inputs": {"vae_name": "minimax_h3_video_vae_fp16.safetensors"},
        },
        "3": {
            "class_type": "VAELoader",
            "inputs": {"vae_name": "minimax_h3_audio_vae_fp32.safetensors"},
        },
        "4": {"class_type": "VAEDecode", "inputs": {"samples": ["1", 0], "vae": ["2", 0]}},
        "5": {"class_type": "VAEDecodeAudio", "inputs": {"samples": ["1", 0], "vae": ["3", 0]}},
        "6": {
            "class_type": "CreateVideo",
            "inputs": {
                "images": ["4", 0],
                "audio": ["5", 0],
                "fps": 24.0,
                "bit_depth": "auto",
                "color_space": "sRGB",
                "codec": "auto",
            },
        },
        "7": {
            "class_type": "SaveVideo",
            "inputs": {
                "video": ["6", 0],
                "filename_prefix": prefix,
                "format": "mp4",
                "format.codec": "h264",
            },
        },
    }


def full_graph(args: argparse.Namespace, seed: int, prefix: str) -> dict:
    graph, model = model_graph(args)
    graph.update(
        {
            "6": {
                "class_type": "CLIPLoader",
                "inputs": {
                    "clip_name": TEXT_ENCODERS[args.text_encoder],
                    "type": "minimax",
                    "device": "default",
                },
            },
            "7": {
                "class_type": "VAELoader",
                "inputs": {"vae_name": "minimax_h3_video_vae_fp16.safetensors"},
            },
            "8": {
                "class_type": "VAELoader",
                "inputs": {"vae_name": "minimax_h3_audio_vae_fp32.safetensors"},
            },
            "9": {
                "class_type": "MiniMaxH3ImageToVideo",
                "inputs": {
                    "clip": ["6", 0],
                    "vae": ["7", 0],
                    "prompt": args.prompt,
                    "width": args.width,
                    "height": args.height,
                    "length": frame_count(args.duration),
                },
            },
            "10": {"class_type": "BasicGuider", "inputs": {"model": model, "conditioning": ["9", 0]}},
            "11": {"class_type": "RandomNoise", "inputs": {"noise_seed": seed}},
            "12": {"class_type": "KSamplerSelect", "inputs": {"sampler_name": "res_multistep"}},
            "13": {
                "class_type": "BasicScheduler",
                "inputs": {"model": model, "scheduler": "simple", "steps": args.steps, "denoise": 1.0},
            },
            "14": {
                "class_type": "SamplerCustomAdvanced",
                "inputs": {
                    "noise": ["11", 0],
                    "guider": ["10", 0],
                    "sampler": ["12", 0],
                    "sigmas": ["13", 0],
                    "latent_image": ["9", 1],
                },
            },
            "15": {"class_type": "VAEDecode", "inputs": {"samples": ["14", 0], "vae": ["7", 0]}},
            "16": {"class_type": "VAEDecodeAudio", "inputs": {"samples": ["14", 0], "vae": ["8", 0]}},
            "17": {
                "class_type": "CreateVideo",
                "inputs": {
                    "images": ["15", 0],
                    "audio": ["16", 0],
                    "fps": 24.0,
                    "bit_depth": "auto",
                    "color_space": "sRGB",
                    "codec": "auto",
                },
            },
            "18": {
                "class_type": "SaveVideo",
                "inputs": {
                    "video": ["17", 0],
                    "filename_prefix": prefix,
                    "format": "mp4",
                    "format.codec": "h264",
                },
            },
        }
    )
    return graph


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--url", default="http://127.0.0.1:8193")
    parser.add_argument("--mode", choices=("single-dense", "dual-dense"), default="dual-dense")
    parser.add_argument("--dit", choices=tuple(MODELS), default="int8")
    parser.add_argument("--text-encoder", choices=tuple(TEXT_ENCODERS), default="int8")
    parser.add_argument("--width", type=int, choices=(832, 1344), default=832)
    parser.add_argument("--height", type=int, choices=(480, 768), default=480)
    parser.add_argument("--duration", type=float, choices=(4.0, 5.0), default=4.0)
    parser.add_argument("--steps", type=int, choices=(4, 8, 10, 20), default=20)
    parser.add_argument("--seed", type=int, default=20261017)
    parser.add_argument("--repeat-seed", type=int, default=20261018)
    parser.add_argument("--exchange-chunks", type=int, choices=(0, 8), default=8)
    parser.add_argument("--cache-key", default="")
    parser.add_argument("--prefix", default="h3-cache/benchmark")
    parser.add_argument("--timeout-seconds", type=int, default=7200)
    parser.add_argument("--skip-baseline", action="store_true")
    parser.add_argument(
        "--prompt",
        default=(
            "Photorealistic cinematic shot inside a modest rural Chinese home. A 30-year-old "
            "Chinese man in a plain white shirt walks from a wooden door toward a table, pauses, "
            "and turns toward warm window light. Slow lateral camera track, stable eyes and hands, "
            "realistic skin, coherent motion, no text. Audio: soft footsteps and distant birds."
        ),
    )
    args = parser.parse_args()
    if (args.width, args.height) not in {(832, 480), (1344, 768)}:
        raise SystemExit("only validated 16:9 canvases are allowed")

    cache_key = args.cache_key or f"cache-{int(time.time())}-{uuid.uuid4().hex[:8]}"
    stages: list[dict] = []
    stages.append({"stage": "encode", **run_graph(
        args.url, encode_graph(args, cache_key), args.timeout_seconds, "h3-cache-encode"
    )})
    for label, seed in (("first", args.seed), ("repeat", args.repeat_seed)):
        stages.append({"stage": f"sample-{label}", **run_graph(
            args.url, sample_graph(args, cache_key, seed), args.timeout_seconds,
            f"h3-cache-sample-{label}",
        )})
        stages.append({"stage": f"decode-{label}", **run_graph(
            args.url,
            decode_graph(args, cache_key, seed, f"{args.prefix}/cached-{label}"),
            args.timeout_seconds,
            f"h3-cache-decode-{label}",
        )})
    if not args.skip_baseline:
        stages.append({"stage": "full-baseline-repeat", **run_graph(
            args.url,
            full_graph(args, args.repeat_seed, f"{args.prefix}/baseline-repeat"),
            args.timeout_seconds,
            "h3-cache-full-baseline",
        )})

    print(json.dumps({
        "cacheKey": cache_key,
        "mode": args.mode,
        "dit": args.dit,
        "textEncoder": args.text_encoder,
        "width": args.width,
        "height": args.height,
        "frames": frame_count(args.duration),
        "steps": args.steps,
        "seed": args.seed,
        "repeatSeed": args.repeat_seed,
        "stages": stages,
    }, ensure_ascii=False, separators=(",", ":")))


if __name__ == "__main__":
    main()
