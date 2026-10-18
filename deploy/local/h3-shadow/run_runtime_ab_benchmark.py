#!/usr/bin/env python3
"""Run one fixed standard H3 sample against a selected ComfyUI runtime."""

from __future__ import annotations

import argparse
import json
import time
import urllib.error
import urllib.request
import uuid


def request_json(url: str, payload: dict | None = None, timeout: int = 60) -> dict:
    data = None if payload is None else json.dumps(payload).encode("utf-8")
    request = urllib.request.Request(
        url, data=data, headers={"Content-Type": "application/json"},
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return json.load(response)
    except urllib.error.HTTPError as exc:
        detail = exc.read().decode("utf-8", "replace")[-4_000:]
        raise RuntimeError(f"HTTP {exc.code}: {detail}") from exc


def frame_count(duration: float) -> int:
    frames = max(5, round(duration * 24))
    return min(362, frames + (5 - frames % 17) % 17)


def build_prompt(args: argparse.Namespace) -> dict:
    return {
        "1": {"class_type": "UNETLoader", "inputs": {
            "unet_name": "minimax_h3_fl2va_pruned_int8_convrot.safetensors",
            "weight_dtype": "default"}},
        "2": {"class_type": "MiniMaxH3SigmaShift", "inputs": {
            "model": ["1", 0], "shift_video": 12.0, "shift_audio": 3.0}},
        "3": {"class_type": "CLIPLoader", "inputs": {
            "clip_name": "qwen3vl_32b_minimax_h3_int8_convrot.safetensors",
            "type": "minimax", "device": "default"}},
        "4": {"class_type": "VAELoaderKJ", "inputs": {
            "vae_name": "minimax_h3_video_vae_fp16.safetensors",
            "device": "main_device", "weight_dtype": "fp16"}},
        "6": {"class_type": "MiniMaxH3ImageToVideo", "inputs": {
            "clip": ["3", 0], "vae": ["4", 0], "prompt": args.prompt,
            "width": args.width, "height": args.height,
            "length": frame_count(args.duration)}},
        "7": {"class_type": "RandomNoise", "inputs": {"noise_seed": args.seed}},
        "8": {"class_type": "KSamplerSelect", "inputs": {
            "sampler_name": "res_multistep"}},
        "9": {"class_type": "BasicGuider", "inputs": {
            "model": ["2", 0], "conditioning": ["6", 0]}},
        "10": {"class_type": "BasicScheduler", "inputs": {
            "model": ["2", 0], "scheduler": "simple", "steps": args.steps,
            "denoise": 1.0}},
        "11": {"class_type": "SamplerCustomAdvanced", "inputs": {
            "noise": ["7", 0], "guider": ["9", 0], "sampler": ["8", 0],
            "sigmas": ["10", 0], "latent_image": ["6", 1]}},
        "12": {"class_type": "VAEDecodeTiled", "inputs": {
            "samples": ["13", 0], "vae": ["4", 0], "tile_size": 256,
            "overlap": 64, "temporal_size": 16, "temporal_overlap": 4}},
        "13": {"class_type": "LTXVSeparateAVLatent", "inputs": {
            "av_latent": ["11", 0]}},
        "14": {"class_type": "CreateVideo", "inputs": {
            "images": ["12", 0], "audio": ["17", 0], "fps": 24.0,
            "bit_depth": 8}},
        "15": {"class_type": "SaveVideo", "inputs": {
            "video": ["14", 0], "filename_prefix": args.prefix,
            "format": "mp4", "codec": "h264"}},
        "16": {"class_type": "VAELoaderKJ", "inputs": {
            "vae_name": "minimax_h3_audio_vae_fp32.safetensors",
            "device": "cpu", "weight_dtype": "fp32"}},
        "17": {"class_type": "VAEDecodeAudio", "inputs": {
            "samples": ["13", 1], "vae": ["16", 0]}},
    }


def unique_node_ids(prompt: dict) -> dict:
    prefix = uuid.uuid4().hex[:12] + "_"
    node_ids = {node_id: prefix + node_id for node_id in prompt}

    def remap(value: object) -> object:
        if (isinstance(value, list) and len(value) == 2
                and isinstance(value[0], str) and value[0] in node_ids
                and isinstance(value[1], int)):
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


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--url", required=True)
    parser.add_argument("--runtime", required=True)
    parser.add_argument("--width", type=int, choices=(832, 1344), default=832)
    parser.add_argument("--height", type=int, choices=(480, 768), default=480)
    parser.add_argument("--duration", type=float, choices=(4.0, 5.0), default=4.0)
    parser.add_argument("--steps", type=int, choices=(20,), default=20)
    parser.add_argument("--seed", type=int, default=20261017)
    parser.add_argument("--prefix", required=True)
    parser.add_argument("--timeout-seconds", type=int, default=7200)
    parser.add_argument("--prompt", default=(
        "Photorealistic cinematic shot inside a modest rural Chinese home. A 30-year-old "
        "Chinese man in a plain white shirt walks from a wooden door toward a table, pauses, "
        "and turns toward warm window light. Slow lateral camera track, stable eyes and hands, "
        "realistic skin, coherent motion, no text. Audio: soft footsteps and distant birds."
    ))
    args = parser.parse_args()
    if (args.width, args.height) not in {(832, 480), (1344, 768)}:
        raise SystemExit("only validated 16:9 canvases are allowed")

    base_url = args.url.rstrip("/")
    started = time.monotonic()
    submitted = request_json(base_url + "/prompt", {
        "prompt": unique_node_ids(build_prompt(args)),
        "client_id": "h3-runtime-ab-benchmark",
    })
    prompt_id = submitted["prompt_id"]
    while time.monotonic() - started < args.timeout_seconds:
        history = request_json(f"{base_url}/history/{prompt_id}")
        if prompt_id in history:
            elapsed = time.monotonic() - started
            result = history[prompt_id]
            status = result.get("status") or {}
            if status.get("completed") is False:
                raise RuntimeError(json.dumps(status, ensure_ascii=False))
            print(json.dumps({
                "promptId": prompt_id, "elapsedSeconds": elapsed,
                "runtime": args.runtime, "steps": args.steps,
                "width": args.width, "height": args.height,
                "frames": frame_count(args.duration), "seed": args.seed,
                "output": output_ref(result),
            }, ensure_ascii=False, separators=(",", ":")))
            return
        time.sleep(5)
    raise TimeoutError("benchmark exceeded its fixed timeout")


if __name__ == "__main__":
    main()
