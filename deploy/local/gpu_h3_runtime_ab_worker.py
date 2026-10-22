#!/usr/bin/env python3
"""Scheduler-only worker for the standard H3 CUDA runtime A/B benchmark."""

from __future__ import annotations

import argparse
import json
import subprocess
import time
import urllib.error
import urllib.request
from pathlib import Path


SCRIPT = Path("/mnt/disk2/h3-runtime-ab/run_runtime_ab_benchmark.py")
PYTHON = "/home/neo/comfyui-fasth3-shadow/env-cu130/bin/python"
RUNTIMES = (
    ("torch-2.8-cu128", "http://127.0.0.1:8188"),
    ("torch-2.9.1-cu130", "http://127.0.0.1:8194"),
)
RUNTIME_NAMES = {name for name, _ in RUNTIMES}


def request_json(url: str, payload: dict | None = None, timeout: int = 60) -> dict:
    data = None if payload is None else json.dumps(payload).encode("utf-8")
    request = urllib.request.Request(
        url, data=data, headers={"Content-Type": "application/json"},
    )
    with urllib.request.urlopen(request, timeout=timeout) as response:
        body = response.read()
        return json.loads(body) if body.strip() else {}


def wait_runtime(url: str, timeout_seconds: int = 900) -> None:
    deadline = time.monotonic() + timeout_seconds
    last_error: Exception | None = None
    required = {
        "UNETLoader", "MiniMaxH3SigmaShift", "CLIPLoader", "VAELoaderKJ",
        "MiniMaxH3ImageToVideo", "RandomNoise", "KSamplerSelect", "BasicGuider",
        "BasicScheduler", "SamplerCustomAdvanced", "VAEDecodeTiled",
        "LTXVSeparateAVLatent", "CreateVideo", "SaveVideo", "VAEDecodeAudio",
    }
    while time.monotonic() < deadline:
        try:
            nodes = request_json(url.rstrip("/") + "/object_info", timeout=20)
            missing = required - set(nodes)
            if missing:
                raise RuntimeError("runtime is missing nodes: " + ", ".join(sorted(missing)))
            return
        except (OSError, ValueError, RuntimeError, urllib.error.URLError) as exc:
            last_error = exc
            time.sleep(2)
    raise RuntimeError(f"runtime did not become ready: {last_error}")


def free_runtime(url: str) -> None:
    request_json(url.rstrip("/") + "/free", {
        "unload_models": True, "free_memory": True,
    })
    time.sleep(5)


def load_job(path: Path) -> dict:
    if not path.is_file() or path.stat().st_size > 1024 * 1024:
        raise RuntimeError("job file is missing or too large")
    job = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(job, dict) or job.get("profile") != "h3-runtime-ab":
        raise RuntimeError("job profile must be h3-runtime-ab")
    return job


def validated(job: dict) -> dict:
    payload = job.get("payload")
    benchmark = payload.get("benchmark") if isinstance(payload, dict) else None
    if not isinstance(benchmark, dict):
        raise RuntimeError("payload.benchmark is required")
    resolution = str(benchmark.get("resolution") or "480P").upper()
    duration = int(benchmark.get("duration") or 4)
    steps = int(benchmark.get("steps") or 20)
    warm_runs = int(benchmark.get("warmRuns") or 1)
    targets = benchmark.get("targets") or [name for name, _ in RUNTIMES]
    if resolution not in {"480P", "768P"}:
        raise RuntimeError("unsupported benchmark resolution")
    if duration not in {4, 5} or steps != 20 or warm_runs not in {1, 2}:
        raise RuntimeError("unsupported benchmark parameters")
    if (not isinstance(targets, list) or not targets
            or any(name not in RUNTIME_NAMES for name in targets)
            or len(set(targets)) != len(targets)):
        raise RuntimeError("unsupported benchmark runtime targets")
    return {
        "resolution": resolution, "duration": duration,
        "steps": steps, "warmRuns": warm_runs, "targets": targets,
    }


def run_once(runtime: str, url: str, item: dict, phase: str, seed: int,
             timeout_seconds: int) -> dict:
    width, height = (1344, 768) if item["resolution"] == "768P" else (832, 480)
    command = [
        PYTHON, str(SCRIPT), "--url", url, "--runtime", runtime,
        "--width", str(width), "--height", str(height),
        "--duration", str(float(item["duration"])), "--steps", str(item["steps"]),
        "--seed", str(seed),
        "--timeout-seconds", str(timeout_seconds),
        "--prefix", f"h3-runtime-ab/{runtime}/{phase}",
    ]
    result = subprocess.run(
        command, text=True, capture_output=True,
        timeout=timeout_seconds + 120, check=False,
    )
    if result.returncode != 0:
        raise RuntimeError((result.stderr or result.stdout or "benchmark failed")[-4_000:])
    lines = [line for line in result.stdout.splitlines() if line.strip()]
    if not lines:
        raise RuntimeError("benchmark returned no result")
    output = json.loads(lines[-1])
    output["phase"] = phase
    return output


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("job_file")
    parser.add_argument("--timeout", type=int, default=7200)
    args = parser.parse_args()
    item = validated(load_job(Path(args.job_file)))
    timeout_seconds = max(600, min(args.timeout, 7200))
    results: list[dict] = []
    try:
        for runtime, url in RUNTIMES:
            if runtime not in item["targets"]:
                continue
            wait_runtime(url)
            free_runtime(url)
            results.append(run_once(
                runtime, url, item, "cold", 20261017, timeout_seconds,
            ))
            for index in range(item["warmRuns"]):
                results.append(run_once(
                    runtime, url, item, f"warm-{index + 1}",
                    20261018 + index, timeout_seconds,
                ))
            free_runtime(url)
    finally:
        for _, url in RUNTIMES:
            try:
                free_runtime(url)
            except Exception:
                pass
    refs = [entry["output"] for entry in results if isinstance(entry.get("output"), dict)]
    print(json.dumps({
        "experiment": "runtime-ab", "results": results,
        "resultRefs": refs, "resultRef": json.dumps(refs, ensure_ascii=False),
    }, ensure_ascii=False, separators=(",", ":")))


if __name__ == "__main__":
    main()
