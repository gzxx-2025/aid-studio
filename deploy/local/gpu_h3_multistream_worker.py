#!/usr/bin/env python3
"""Allow-listed scheduler worker for isolated H3 MultiStream benchmarks."""

from __future__ import annotations

import argparse
import json
import subprocess
import time
import urllib.error
import urllib.request
from pathlib import Path


SCRIPT = Path("/mnt/disk2/h3-multistream-lab/run_multistream_benchmark.py")
CACHE_SCRIPT = Path("/mnt/disk2/h3-multistream-lab/run_conditioning_cache_benchmark.py")
REFERENCE_CACHE_SCRIPT = Path("/mnt/disk2/h3-multistream-lab/run_reference_cache_benchmark.py")
FIRSTBLOCK_CACHE_SCRIPT = Path("/mnt/disk2/h3-multistream-lab/run_firstblock_cache_benchmark.py")
PDD_SAGE_SCRIPT = Path("/mnt/disk2/h3-multistream-lab/run_pdd_sage_benchmark.py")
ALLOWED_MODES = {"single-dense", "dual-dense", "dual-sol", "dual-vsa"}
REFERENCE_NODES = {"LoadImage", "LoraLoaderModelOnly", "MiniMaxH3ReferenceToVideo"}
FIRSTBLOCK_NODES = REFERENCE_NODES | {"ApplyMiniMaxH3FirstBlockCache"}
PDD_NODES = {"MiniMaxH3PDDAccApply"}
RUNTIME_SERVICE = "comfyui-h3-multistream.service"


def validate_runtime_attention(requested: str) -> None:
    result = subprocess.run(
        ["systemctl", "--user", "show", RUNTIME_SERVICE, "-p", "MainPID", "--value"],
        text=True, capture_output=True, check=False,
    )
    if result.returncode != 0 or not result.stdout.strip().isdigit():
        raise RuntimeError("cannot resolve MultiStream runtime process")
    pid = int(result.stdout.strip())
    if pid <= 0:
        raise RuntimeError("MultiStream runtime is not running")
    command = Path(f"/proc/{pid}/cmdline").read_bytes().replace(b"\0", b" ").decode(
        "utf-8", errors="replace"
    )
    sage_enabled = "--use-sage-attention" in command
    if requested == "sage" and not sage_enabled:
        raise RuntimeError("SageAttention requested but runtime was not started with it")
    if requested == "kitchen" and sage_enabled:
        raise RuntimeError("baseline requested but runtime has SageAttention enabled")


def wait_runtime(timeout_seconds: int = 900, required_extra: set[str] | None = None) -> None:
    deadline = time.monotonic() + timeout_seconds
    last_error: Exception | None = None
    while time.monotonic() < deadline:
        try:
            with urllib.request.urlopen("http://127.0.0.1:8193/object_info", timeout=15) as response:
                nodes = json.load(response)
            required = {
                "H3MultiStream", "BlockSparseAttention", "MiniMaxH3ImageToVideo",
                "H3SaveConditioning", "H3LoadConditioning",
                "H3SaveLatentAV", "H3LoadLatentAV",
            }
            required.update(required_extra or set())
            missing = required - set(nodes)
            if missing:
                raise RuntimeError("runtime is missing nodes: " + ", ".join(sorted(missing)))
            return
        except (OSError, ValueError, RuntimeError, urllib.error.URLError) as exc:
            last_error = exc
            time.sleep(2)
    raise RuntimeError(f"MultiStream runtime did not become ready: {last_error}")


def load_job(path: Path) -> dict:
    if not path.is_file() or path.stat().st_size > 1024 * 1024:
        raise RuntimeError("job file is missing or too large")
    job = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(job, dict) or job.get("profile") != "h3-dual-dense":
        raise RuntimeError("job profile must be h3-dual-dense")
    return job


def validated(job: dict) -> dict:
    payload = job.get("payload")
    benchmark = payload.get("benchmark") if isinstance(payload, dict) else None
    if not isinstance(benchmark, dict):
        raise RuntimeError("payload.benchmark is required")
    experiment = str(benchmark.get("experiment") or "multistream")
    mode = str(benchmark.get("mode") or "")
    model = str(benchmark.get("model") or "standard")
    resolution = str(benchmark.get("resolution") or "480P").upper()
    duration = int(benchmark.get("duration") or 4)
    steps = int(benchmark.get("steps") or (8 if model == "fast" else 20))
    chunks = int(benchmark.get("exchangeChunks") or 8)
    if experiment not in {
        "multistream", "conditioning-cache", "reference-cache", "firstblock-cache",
        "pdd-sage",
    }:
        raise RuntimeError("unsupported benchmark experiment")
    if mode not in ALLOWED_MODES:
        raise RuntimeError("unsupported benchmark mode")
    if model not in {"standard", "fast"} or resolution not in {"480P", "768P"}:
        raise RuntimeError("unsupported model or resolution")
    if duration not in {4, 5} or steps not in {4, 8, 20} or chunks not in {0, 8}:
        raise RuntimeError("unsupported benchmark parameters")
    if mode == "dual-vsa" and model != "fast":
        raise RuntimeError("dual-vsa requires model=fast")
    if model == "fast" and steps != 8:
        raise RuntimeError("FastH3 requires 8 steps")
    dit = str(benchmark.get("dit") or "int8")
    text_encoder = str(benchmark.get("textEncoder") or "int8")
    skip_baseline = bool(benchmark.get("skipBaseline", False))
    acceleration = str(benchmark.get("acceleration") or "standard")
    attention = str(benchmark.get("attention") or "kitchen")
    if experiment == "pdd-sage":
        if mode != "dual-dense" or model != "standard" or resolution != "768P" or duration != 5:
            raise RuntimeError("pdd-sage validation requires dual standard 768P for 5 seconds")
        if acceleration not in {"standard", "pdd"} or attention not in {"kitchen", "sage"}:
            raise RuntimeError("unsupported pdd-sage variant")
        allowed_steps = {20} if acceleration == "standard" else {4, 6, 8}
        if steps not in allowed_steps:
            raise RuntimeError("pdd-sage steps do not match the selected acceleration")
    if experiment == "conditioning-cache":
        if mode not in {"single-dense", "dual-dense"} or model != "standard":
            raise RuntimeError("conditioning-cache requires the standard dense model")
        if dit not in {"int8", "w6a8"} or text_encoder not in {"int8", "nvfp4"}:
            raise RuntimeError("unsupported conditioning-cache quantization")
    if experiment in {"reference-cache", "firstblock-cache"}:
        references = benchmark.get("referenceImages")
        if mode not in {"single-dense", "dual-dense"} or model != "standard":
            raise RuntimeError(f"{experiment} requires the standard dense model")
        expected_steps = 20 if experiment == "firstblock-cache" else 4
        if resolution != "480P" or steps != expected_steps:
            raise RuntimeError(
                f"{experiment} validation requires 480P and {expected_steps} steps"
            )
        if not isinstance(references, list) or not 2 <= len(references) <= 3:
            raise RuntimeError(f"{experiment} requires two or three images")
        if any(not isinstance(name, str) or not name or "/" in name or "\\" in name for name in references):
            raise RuntimeError(f"{experiment} image names must be input-directory basenames")
    else:
        references = []
    return {
        "experiment": experiment, "mode": mode, "model": model, "resolution": resolution,
        "duration": duration, "steps": steps, "exchangeChunks": chunks,
        "dit": dit, "textEncoder": text_encoder, "skipBaseline": skip_baseline,
        "referenceImages": references, "acceleration": acceleration,
        "attention": attention,
    }


def result_refs(output: dict) -> list[dict]:
    refs: list[dict] = []
    direct = output.get("output")
    if isinstance(direct, dict):
        refs.append(direct)
    stages = output.get("stages")
    if isinstance(stages, list):
        for stage in stages:
            ref = stage.get("output") if isinstance(stage, dict) else None
            if isinstance(ref, dict):
                refs.append(ref)
    return refs


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("job_file")
    parser.add_argument("--timeout", type=int, default=7200)
    args = parser.parse_args()
    item = validated(load_job(Path(args.job_file)))
    extra_nodes = None
    if item["experiment"] == "reference-cache":
        extra_nodes = REFERENCE_NODES
    elif item["experiment"] == "firstblock-cache":
        extra_nodes = FIRSTBLOCK_NODES
    elif item["experiment"] == "pdd-sage" and item["acceleration"] == "pdd":
        extra_nodes = PDD_NODES
    wait_runtime(required_extra=extra_nodes)
    if item["experiment"] == "pdd-sage":
        validate_runtime_attention(item["attention"])
    width, height = (1344, 768) if item["resolution"] == "768P" else (832, 480)
    if item["experiment"] == "pdd-sage":
        command = [
            "/home/neo/comfyui-fasth3-shadow/env-cu130/bin/python", str(PDD_SAGE_SCRIPT),
            "--acceleration", item["acceleration"], "--attention", item["attention"],
            "--width", str(width), "--height", str(height),
            "--duration", str(float(item["duration"])), "--steps", str(item["steps"]),
            "--timeout-seconds", str(max(300, min(args.timeout, 7200))),
            "--prefix", f"h3-pdd-sage/{item['acceleration']}-{item['attention']}",
        ]
    elif item["experiment"] == "firstblock-cache":
        command = [
            "/home/neo/comfyui-fasth3-shadow/env-cu130/bin/python", str(FIRSTBLOCK_CACHE_SCRIPT),
            "--width", str(width), "--height", str(height),
            "--duration", str(float(item["duration"])),
            "--timeout-seconds", str(max(300, min(args.timeout, 7200))),
            "--prefix", "h3-firstblock-cache/multi-reference",
        ]
        for image_name in item["referenceImages"]:
            command.extend(["--reference-image", image_name])
    elif item["experiment"] == "reference-cache":
        command = [
            "/home/neo/comfyui-fasth3-shadow/env-cu130/bin/python", str(REFERENCE_CACHE_SCRIPT),
            "--mode", item["mode"], "--width", str(width), "--height", str(height),
            "--duration", str(float(item["duration"])),
            "--exchange-chunks", str(item["exchangeChunks"]),
            "--timeout-seconds", str(max(300, min(args.timeout, 7200))),
            "--prefix", f"h3-reference-cache/{item['mode']}",
        ]
        for image_name in item["referenceImages"]:
            command.extend(["--reference-image", image_name])
        if item["skipBaseline"]:
            command.append("--skip-baseline")
    elif item["experiment"] == "conditioning-cache":
        command = [
            "/home/neo/comfyui-fasth3-shadow/env-cu130/bin/python", str(CACHE_SCRIPT),
            "--mode", item["mode"], "--dit", item["dit"],
            "--text-encoder", item["textEncoder"],
            "--width", str(width), "--height", str(height),
            "--duration", str(float(item["duration"])), "--steps", str(item["steps"]),
            "--exchange-chunks", str(item["exchangeChunks"]),
            "--timeout-seconds", str(max(300, min(args.timeout, 7200))),
            "--prefix", f"h3-cache/{item['mode']}-{item['dit']}-{item['textEncoder']}",
        ]
        if item["skipBaseline"]:
            command.append("--skip-baseline")
    else:
        command = [
            "/home/neo/comfyui-fasth3-shadow/env-cu130/bin/python", str(SCRIPT),
            "--mode", item["mode"], "--model", item["model"],
            "--width", str(width), "--height", str(height),
            "--duration", str(float(item["duration"])), "--steps", str(item["steps"]),
            "--exchange-chunks", str(item["exchangeChunks"]),
            "--timeout-seconds", str(max(300, min(args.timeout, 7200))),
            "--prefix", f"h3-multistream/{item['mode']}",
        ]
    result = subprocess.run(command, text=True, capture_output=True, timeout=7500, check=False)
    if result.returncode != 0:
        raise RuntimeError((result.stderr or result.stdout or "benchmark failed")[-4000:])
    lines = [line for line in result.stdout.splitlines() if line.strip()]
    if not lines:
        raise RuntimeError("benchmark returned no result")
    output = json.loads(lines[-1])
    refs = result_refs(output)
    output["resultRefs"] = refs
    output["resultRef"] = json.dumps(refs, ensure_ascii=False)
    print(json.dumps(output, ensure_ascii=False, separators=(",", ":")))


if __name__ == "__main__":
    main()
