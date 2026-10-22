#!/usr/bin/env python3
"""Benchmark lossless MiniMax H3 multi-reference conditioning/latent caching."""

from __future__ import annotations

import argparse
import json
import time
import uuid

import run_conditioning_cache_benchmark as common


REF_MODEL = "minimax_h3_ref2va_pruned_int8_convrot.safetensors"
REF_LORA = "minimax_h3_ref2v_turbo_4step_v0.1_comfyui_bf16.safetensors"
TEXT_ENCODER = "qwen3vl_32b_minimax_h3_int8_convrot.safetensors"
DEFAULT_PROMPT = (
    "Use <Picture 1> as the exact identity, face, hairstyle and dark suit of the adult "
    "Chinese man. Use <Picture 2> as the exact identity, face, hairstyle and floral blouse "
    "of the adult Chinese woman. Both stand beside a rural river in summer; the man turns "
    "toward the woman and she smiles subtly. Medium two-shot, stable faces, realistic skin, "
    "gentle camera move, no text. Audio: river water and cicadas."
)


def model_graph(args: argparse.Namespace) -> tuple[dict, list[object]]:
    graph = {
        "1": {
            "class_type": "UNETLoader",
            "inputs": {"unet_name": REF_MODEL, "weight_dtype": "default"},
        },
        "19": {
            "class_type": "LoraLoaderModelOnly",
            "inputs": {"model": ["1", 0], "lora_name": REF_LORA, "strength_model": 1.0},
        },
        "2": {
            "class_type": "MiniMaxH3SigmaShift",
            "inputs": {"model": ["19", 0], "shift_video": 12.0, "shift_audio": 3.0},
        },
        "3": {
            "class_type": "ModelAttentionBackend",
            "inputs": {"model": ["2", 0], "attention": "comfy kitchen attention"},
        },
        "4": {
            "class_type": "H3MultiStream",
            "inputs": {
                "model": ["3", 0],
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


def reference_inputs(args: argparse.Namespace, start_node: int = 20) -> tuple[dict, dict]:
    nodes: dict = {}
    inputs: dict = {}
    for index, image_name in enumerate(args.reference_images):
        node_id = str(start_node + index)
        nodes[node_id] = {"class_type": "LoadImage", "inputs": {"image": image_name}}
        inputs[f"ref_images.ref_image_{index}"] = [node_id, 0]
    return nodes, inputs


def encode_graph(args: argparse.Namespace, cache_key: str) -> dict:
    image_nodes, image_inputs = reference_inputs(args)
    graph = {
        "1": {
            "class_type": "CLIPLoader",
            "inputs": {"clip_name": TEXT_ENCODER, "type": "minimax", "device": "default"},
        },
        "2": {
            "class_type": "VAELoader",
            "inputs": {"vae_name": "minimax_h3_video_vae_fp16.safetensors"},
        },
        "3": {
            "class_type": "VAELoader",
            "inputs": {"vae_name": "minimax_h3_audio_vae_fp32.safetensors"},
        },
        "4": {
            "class_type": "MiniMaxH3ReferenceToVideo",
            "inputs": {
                "clip": ["1", 0],
                "vae": ["2", 0],
                "audio_vae": ["3", 0],
                "prompt": args.prompt,
                "width": args.width,
                "height": args.height,
                "length": common.frame_count(args.duration),
                "ref_image_size": "match",
                **image_inputs,
            },
        },
        "5": {
            "class_type": "H3SaveConditioning",
            "inputs": {"conditioning": ["4", 0], "filename": cache_key},
        },
        "6": {
            "class_type": "H3SaveLatentAV",
            "inputs": {"samples": ["4", 1], "filename": f"{cache_key}-input"},
        },
    }
    graph.update(image_nodes)
    return graph


def sample_graph(args: argparse.Namespace, cache_key: str, seed: int) -> dict:
    graph, model = model_graph(args)
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
        "10": {"class_type": "KSamplerSelect", "inputs": {"sampler_name": "euler"}},
        "11": {
            "class_type": "BasicScheduler",
            "inputs": {"model": model, "scheduler": "simple", "steps": 4, "denoise": 1.0},
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
            "inputs": {"samples": ["12", 0], "filename": f"{cache_key}-{seed}"},
        },
    })
    return graph


def full_graph(args: argparse.Namespace, seed: int, prefix: str) -> dict:
    graph, model = model_graph(args)
    image_nodes, image_inputs = reference_inputs(args)
    graph.update({
        "6": {
            "class_type": "CLIPLoader",
            "inputs": {"clip_name": TEXT_ENCODER, "type": "minimax", "device": "default"},
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
            "class_type": "MiniMaxH3ReferenceToVideo",
            "inputs": {
                "clip": ["6", 0], "vae": ["7", 0], "audio_vae": ["8", 0],
                "prompt": args.prompt, "width": args.width, "height": args.height,
                "length": common.frame_count(args.duration), "ref_image_size": "match",
                **image_inputs,
            },
        },
        "10": {"class_type": "BasicGuider", "inputs": {"model": model, "conditioning": ["9", 0]}},
        "11": {"class_type": "RandomNoise", "inputs": {"noise_seed": seed}},
        "12": {"class_type": "KSamplerSelect", "inputs": {"sampler_name": "euler"}},
        "13": {
            "class_type": "BasicScheduler",
            "inputs": {"model": model, "scheduler": "simple", "steps": 4, "denoise": 1.0},
        },
        "14": {
            "class_type": "SamplerCustomAdvanced",
            "inputs": {
                "noise": ["11", 0], "guider": ["10", 0], "sampler": ["12", 0],
                "sigmas": ["13", 0], "latent_image": ["9", 1],
            },
        },
        "15": {"class_type": "VAEDecode", "inputs": {"samples": ["14", 0], "vae": ["7", 0]}},
        "16": {"class_type": "VAEDecodeAudio", "inputs": {"samples": ["14", 0], "vae": ["8", 0]}},
        "17": {
            "class_type": "CreateVideo",
            "inputs": {
                "images": ["15", 0], "audio": ["16", 0], "fps": 24.0,
                "bit_depth": "auto", "color_space": "sRGB", "codec": "auto",
            },
        },
        "18": {
            "class_type": "SaveVideo",
            "inputs": {
                "video": ["17", 0], "filename_prefix": prefix,
                "format": "mp4", "format.codec": "h264",
            },
        },
    })
    graph.update(image_nodes)
    return graph


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--url", default="http://127.0.0.1:8193")
    parser.add_argument("--mode", choices=("single-dense", "dual-dense"), default="dual-dense")
    parser.add_argument("--reference-image", dest="reference_images", action="append", required=True)
    parser.add_argument("--width", type=int, default=832)
    parser.add_argument("--height", type=int, default=480)
    parser.add_argument("--duration", type=float, choices=(4.0, 5.0), default=4.0)
    parser.add_argument("--seed", type=int, default=20261017)
    parser.add_argument("--repeat-seed", type=int, default=20261018)
    parser.add_argument("--exchange-chunks", type=int, choices=(0, 8), default=8)
    parser.add_argument("--cache-key", default="")
    parser.add_argument("--prefix", default="h3-reference-cache/benchmark")
    parser.add_argument("--timeout-seconds", type=int, default=7200)
    parser.add_argument("--skip-baseline", action="store_true")
    parser.add_argument(
        "--prompt",
        default=DEFAULT_PROMPT,
    )
    args = parser.parse_args()
    if (args.width, args.height) != (832, 480):
        raise SystemExit("reference-cache validation is initially restricted to 832x480")
    if not 2 <= len(args.reference_images) <= 3:
        raise SystemExit("reference-cache validation requires two or three ordered images")

    cache_key = args.cache_key or f"ref-cache-{int(time.time())}-{uuid.uuid4().hex[:8]}"
    stages: list[dict] = []
    stages.append({"stage": "encode-references", **common.run_graph(
        args.url, encode_graph(args, cache_key), args.timeout_seconds, "h3-ref-cache-encode"
    )})
    for label, seed in (("first", args.seed), ("repeat", args.repeat_seed)):
        stages.append({"stage": f"sample-{label}", **common.run_graph(
            args.url, sample_graph(args, cache_key, seed), args.timeout_seconds,
            f"h3-ref-cache-sample-{label}",
        )})
        stages.append({"stage": f"decode-{label}", **common.run_graph(
            args.url,
            common.decode_graph(args, cache_key, seed, f"{args.prefix}/cached-{label}"),
            args.timeout_seconds,
            f"h3-ref-cache-decode-{label}",
        )})
    if not args.skip_baseline:
        stages.append({"stage": "full-baseline-repeat", **common.run_graph(
            args.url,
            full_graph(args, args.repeat_seed, f"{args.prefix}/baseline-repeat"),
            args.timeout_seconds,
            "h3-ref-cache-full-baseline",
        )})

    print(json.dumps({
        "cacheKey": cache_key,
        "mode": args.mode,
        "inputMode": "multi-reference-image",
        "referenceImages": args.reference_images,
        "width": args.width,
        "height": args.height,
        "frames": common.frame_count(args.duration),
        "steps": 4,
        "sampler": "euler",
        "seed": args.seed,
        "repeatSeed": args.repeat_seed,
        "stages": stages,
    }, ensure_ascii=False, separators=(",", ":")))


if __name__ == "__main__":
    main()
