#!/usr/bin/env python3
"""Run a fixed MiniMax H3 single/dual-GPU benchmark through ComfyUI."""

from __future__ import annotations

import argparse
import json
import time
import urllib.error
import urllib.request
import uuid


MODELS = {
    "standard": "minimax_h3_fl2va_pruned_int8_convrot.safetensors",
    "fast": "fastvideo_fasth3_8step_v2_pruned_int8_convrot.safetensors",
}


def request_json(url: str, payload: dict | None = None) -> dict:
    data = None if payload is None else json.dumps(payload).encode("utf-8")
    request = urllib.request.Request(url, data=data, headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            return json.load(response)
    except urllib.error.HTTPError as exc:
        detail = exc.read().decode("utf-8", "replace")[-4_000:]
        raise RuntimeError(f"HTTP {exc.code}: {detail}") from exc


def frame_count(duration: float) -> int:
    frames = max(5, round(duration * 24))
    return min(362, frames + (5 - frames % 17) % 17)


def build_prompt(args: argparse.Namespace) -> dict:
    sparse = args.mode in {"dual-sol", "dual-vsa"}
    vsa = args.mode == "dual-vsa"
    enabled = args.mode != "single-dense"
    if vsa and args.model != "fast":
        raise ValueError("dual-vsa requires the FastH3 VSA-trained checkpoint")
    if args.model == "fast" and args.steps != 8:
        raise ValueError("FastH3 is fixed to its trained 8-step schedule")

    model_source: list[object] = ["3", 0]
    prompt = {
        "1": {"class_type": "UNETLoader", "inputs": {
            "unet_name": MODELS[args.model], "weight_dtype": "default"}},
        "2": {"class_type": "MiniMaxH3SigmaShift", "inputs": {
            "model": ["1", 0], "shift_video": 10.0 if args.model == "fast" else 12.0,
            "shift_audio": 3.0}},
        "3": {"class_type": "ModelAttentionBackend", "inputs": {
            "model": ["2", 0], "attention": "comfy kitchen attention"}},
        "5": {"class_type": "H3MultiStream", "inputs": {
            "model": model_source, "enabled": enabled, "second_gpu": 1,
            "exchange": "host", "exchange_chunks": args.exchange_chunks,
            "sparse_attention": sparse, "dynamic_vram": "keep",
            "vram_block_cache": False, "vram_reserve_gb": 4.0,
            "sparse_vsa": vsa, "weight_cache": True,
            "cache_ram_reserve_gb": 24.0}},
        "19": {"class_type": "H3BenchmarkForceRun", "inputs": {
            "model": ["5", 0]}},
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
            "length": frame_count(args.duration)}},
        "10": {"class_type": "BasicGuider", "inputs": {
            "model": ["19", 0], "conditioning": ["9", 0]}},
        "11": {"class_type": "RandomNoise", "inputs": {"noise_seed": args.seed}},
        "12": {"class_type": "KSamplerSelect", "inputs": {
            "sampler_name": "res_multistep"}},
        "13": {"class_type": "BasicScheduler", "inputs": {
            "model": ["19", 0], "scheduler": "simple", "steps": args.steps,
            "denoise": 1.0}},
        "14": {"class_type": "SamplerCustomAdvanced", "inputs": {
            "noise": ["11", 0], "guider": ["10", 0], "sampler": ["12", 0],
            "sigmas": ["13", 0], "latent_image": ["9", 1]}},
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
    if sparse:
        selection = "vsa" if vsa else "sol-attn"
        sparse_inputs = {
            "model": ["3", 0], "selection": selection,
            "start_percent": args.sparse_start, "end_percent": 1.0,
            "dense_blocks": "", "min_tokens": args.min_tokens,
            "extra_tokens": 256, "sink_conditioning": "exact_kv_and_rows",
            "verbose": True,
        }
        sparse_inputs["selection.keep_percent" if vsa else "selection.tau"] = (
            10.0 if vsa else args.sol_tau)
        prompt["4"] = {"class_type": "BlockSparseAttention", "inputs": sparse_inputs}
        prompt["5"]["inputs"]["model"] = ["4", 0]
    return prompt


def unique_node_ids(prompt: dict) -> dict:
    """Force a real execution without changing seed, prompt, or model inputs."""
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
    parser.add_argument("--url", default="http://127.0.0.1:8193")
    parser.add_argument("--mode", choices=("single-dense", "dual-dense", "dual-sol", "dual-vsa"), required=True)
    parser.add_argument("--model", choices=tuple(MODELS), default="standard")
    parser.add_argument("--width", type=int, choices=(832, 1344), default=832)
    parser.add_argument("--height", type=int, choices=(480, 768), default=480)
    parser.add_argument("--duration", type=float, choices=(4.0, 5.0), default=4.0)
    parser.add_argument("--steps", type=int, choices=(4, 8, 20), default=20)
    parser.add_argument("--seed", type=int, default=20261017)
    parser.add_argument("--exchange-chunks", type=int, choices=(0, 8), default=8)
    parser.add_argument("--sparse-start", type=float, choices=(0.1, 0.2, 0.3), default=0.2)
    parser.add_argument("--sol-tau", type=float, choices=(1.2, 1.3, 1.4), default=1.3)
    parser.add_argument("--min-tokens", type=int, choices=(8192, 12288), default=8192)
    parser.add_argument("--prefix", default="h3-multistream/benchmark")
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

    started = time.monotonic()
    submitted = request_json(args.url.rstrip("/") + "/prompt", {
        "prompt": unique_node_ids(build_prompt(args)),
        "client_id": "h3-multistream-benchmark"})
    prompt_id = submitted["prompt_id"]
    while time.monotonic() - started < args.timeout_seconds:
        history = request_json(f"{args.url.rstrip('/')}/history/{prompt_id}")
        if prompt_id in history:
            elapsed = time.monotonic() - started
            result = history[prompt_id]
            status = result.get("status") or {}
            if status.get("completed") is False:
                raise RuntimeError(json.dumps(status, ensure_ascii=False))
            print(json.dumps({
                "promptId": prompt_id, "elapsedSeconds": elapsed,
                "mode": args.mode, "model": args.model, "steps": args.steps,
                "width": args.width, "height": args.height,
                "frames": frame_count(args.duration), "seed": args.seed,
                "exchangeChunks": args.exchange_chunks,
                "output": output_ref(result),
            }, ensure_ascii=False, separators=(",", ":")))
            return
        time.sleep(5)
    raise TimeoutError("benchmark exceeded its fixed timeout")


if __name__ == "__main__":
    main()
