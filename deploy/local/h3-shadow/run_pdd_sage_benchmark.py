#!/usr/bin/env python3
"""Benchmark 768P H3 Standard/PDD with kitchen or Sage attention."""

from __future__ import annotations

import argparse
import json
import time

import run_multistream_benchmark as common


PDD_FILE = "MiniMax-H3-FL2VA-Acc-8Step.safetensors"


def build_prompt(args: argparse.Namespace, attention: str) -> dict:
    if args.acceleration == "standard" and args.steps != 20:
        raise ValueError("standard validation requires 20 steps")
    if args.acceleration == "pdd" and args.steps not in {4, 6, 8}:
        raise ValueError("PDD validation requires 4, 6, or 8 trained steps")

    prompt = {
        "1": {"class_type": "UNETLoader", "inputs": {
            "unet_name": common.MODELS["standard"], "weight_dtype": "default"}},
        "2": {"class_type": "MiniMaxH3SigmaShift", "inputs": {
            "model": ["1", 0], "shift_video": 12.0, "shift_audio": 3.0}},
        "5": {"class_type": "H3MultiStream", "inputs": {
            "model": ["2", 0], "enabled": True, "second_gpu": 1,
            "exchange": "host", "exchange_chunks": 8,
            "sparse_attention": False, "dynamic_vram": "keep",
            "vram_block_cache": False, "vram_reserve_gb": 4.0,
            "sparse_vsa": False, "weight_cache": True,
            "cache_ram_reserve_gb": 24.0}},
        "19": {"class_type": "H3BenchmarkForceRun", "inputs": {"model": ["5", 0]}},
        "6": {"class_type": "CLIPLoader", "inputs": {
            "clip_name": "qwen3vl_32b_minimax_h3_int8_convrot.safetensors",
            "type": "minimax", "device": "default"}},
        "7": {"class_type": "VAELoader", "inputs": {
            "vae_name": "minimax_h3_video_vae_fp16.safetensors"}},
        "8": {"class_type": "VAELoader", "inputs": {
            "vae_name": "minimax_h3_audio_vae_fp32.safetensors"}},
        "9": {"class_type": "MiniMaxH3ImageToVideo", "inputs": {
            "clip": ["6", 0], "vae": ["7", 0], "prompt": args.prompt,
            "width": args.width, "height": args.height,
            "length": common.frame_count(args.duration)}},
        "10": {"class_type": "BasicGuider", "inputs": {
            "model": ["19", 0], "conditioning": ["9", 0]}},
        "11": {"class_type": "RandomNoise", "inputs": {"noise_seed": args.seed}},
        "12": {"class_type": "KSamplerSelect", "inputs": {
            "sampler_name": "euler" if args.acceleration == "pdd" else "res_multistep"}},
        "14": {"class_type": "SamplerCustomAdvanced", "inputs": {
            "noise": ["11", 0], "guider": ["10", 0], "sampler": ["12", 0],
            "sigmas": ["13", 1] if args.acceleration == "pdd" else ["13", 0],
            "latent_image": ["9", 1]}},
        "15": {"class_type": "VAEDecode", "inputs": {
            "samples": ["14", 0], "vae": ["7", 0]}},
        "16": {"class_type": "VAEDecodeAudio", "inputs": {
            "samples": ["14", 0], "vae": ["8", 0]}},
        "17": {"class_type": "CreateVideo", "inputs": {
            "images": ["15", 0], "audio": ["16", 0], "fps": 24.0,
            "bit_depth": "auto", "color_space": "sRGB", "codec": "auto"}},
        "18": {"class_type": "SaveVideo", "inputs": {
            "video": ["17", 0], "filename_prefix": args.prefix,
            "format": "mp4", "format.codec": "h264"}},
    }
    if args.acceleration == "pdd":
        prompt["13"] = {"class_type": "MiniMaxH3PDDAccApply", "inputs": {
            "model": ["2", 0], "pdd_file": PDD_FILE, "nfe": str(args.steps),
            "lora_strength": 1.0, "head_strength": 1.0,
            "on_off_grid": "error", "partition_check": "error"}}
        prompt["5"]["inputs"]["model"] = ["13", 0]
    else:
        prompt["13"] = {"class_type": "BasicScheduler", "inputs": {
            "model": ["19", 0], "scheduler": "simple", "steps": args.steps,
            "denoise": 1.0}}
    return prompt


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--url", default="http://127.0.0.1:8193")
    parser.add_argument("--acceleration", choices=("standard", "pdd"), required=True)
    parser.add_argument("--attention", choices=("kitchen", "sage"), required=True)
    parser.add_argument("--width", type=int, default=1344)
    parser.add_argument("--height", type=int, default=768)
    parser.add_argument("--duration", type=float, choices=(5.0,), default=5.0)
    parser.add_argument("--steps", type=int, choices=(4, 6, 8, 20), required=True)
    parser.add_argument("--seed", type=int, default=20261017)
    parser.add_argument("--prefix", default="h3-pdd-sage/benchmark")
    parser.add_argument("--timeout-seconds", type=int, default=7200)
    parser.add_argument("--prompt", default=(
        "Photorealistic cinematic shot inside a modest rural Chinese home. A 30-year-old "
        "Chinese man in a plain white shirt walks from a wooden door toward a table, pauses, "
        "and turns toward warm window light. Slow lateral camera track, stable eyes and hands, "
        "realistic skin, coherent motion, no text. Audio: soft footsteps and distant birds."
    ))
    args = parser.parse_args()
    if (args.width, args.height) != (1344, 768):
        raise SystemExit("this acceptance is restricted to native 1344x768")

    started = time.monotonic()
    submitted = common.request_json(args.url.rstrip("/") + "/prompt", {
        "prompt": common.unique_node_ids(build_prompt(args, args.attention)),
        "client_id": "h3-pdd-sage-benchmark",
    })
    prompt_id = submitted["prompt_id"]
    while time.monotonic() - started < args.timeout_seconds:
        history = common.request_json(f"{args.url.rstrip('/')}/history/{prompt_id}")
        if prompt_id in history:
            elapsed = time.monotonic() - started
            result = history[prompt_id]
            status = result.get("status") or {}
            if status.get("completed") is False:
                raise RuntimeError(json.dumps(status, ensure_ascii=False))
            print(json.dumps({
                "promptId": prompt_id, "elapsedSeconds": elapsed,
                "acceleration": args.acceleration, "attention": args.attention,
                "steps": args.steps, "width": args.width, "height": args.height,
                "frames": common.frame_count(args.duration), "seed": args.seed,
                "output": common.output_ref(result),
            }, ensure_ascii=False, separators=(",", ":")))
            return
        time.sleep(5)
    raise TimeoutError("benchmark exceeded its fixed timeout")


if __name__ == "__main__":
    main()
