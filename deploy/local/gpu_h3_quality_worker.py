#!/usr/bin/env python3
"""Fixed standard/Turbo/reference H3 worker for the gpu-222 scheduler."""

from __future__ import annotations

import argparse
import json
import os
import time
from pathlib import Path
from typing import Any


os.environ.setdefault("COMFY_URL", "http://127.0.0.1:8188")
os.environ.setdefault("H3_TURBO_ENABLED", "true")
os.environ.setdefault("H3_FAST_VSA_ENABLED", "false")
os.environ.setdefault("H3_VEDA_ENABLED", "false")
os.environ.setdefault("H3_RESOURCE_RELEASE_ENABLED", "false")
os.environ.setdefault("H3_MAX_SAFE_DURATION", "10")
os.environ.setdefault("H3_LONG_SHOT_POLICY", "reject")
os.environ.setdefault("H3_QUALITY_RESULT_BASE", "http://10.190.0.222:8188")

import comfy_h3_adapter as h3  # noqa: E402


REQUIRED_NODES = {
    "CLIPLoader",
    "CreateVideo",
    "GetVideoComponents",
    "LoadAudio",
    "LoadImage",
    "LoadVideo",
    "LoraLoaderModelOnly",
    "MiniMaxH3ImageToVideo",
    "MiniMaxH3ReferenceToVideo",
    "MiniMaxH3SigmaShift",
    "SaveVideo",
    "UNETLoader",
    "VAEDecodeAudio",
    "VAEDecodeTiled",
    "VAELoaderKJ",
}
TERMINAL = {"succeeded", "failed"}


def load_job(path: Path) -> dict[str, Any]:
    if not path.is_file() or path.stat().st_size > 2 * 1024 * 1024:
        raise RuntimeError("job file is missing or exceeds 2 MiB")
    job = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(job, dict) or job.get("profile") != "h3-standard-quality":
        raise RuntimeError("job profile must be h3-standard-quality")
    return job


def validated_request(job: dict[str, Any]) -> dict[str, Any]:
    payload = job.get("payload")
    request = payload.get("videoRequest") if isinstance(payload, dict) else None
    if not isinstance(request, dict):
        raise RuntimeError("payload.videoRequest must be a JSON object")
    request = json.loads(json.dumps(request, ensure_ascii=False))
    acceleration = str(request.get("acceleration") or "stable").strip().lower()
    if acceleration not in {"stable", "turbo"}:
        raise RuntimeError("quality adapter supports only stable or turbo")
    resolution = str(request.get("resolution") or "768P").upper()
    if resolution not in {"480P", "768P"}:
        raise RuntimeError("quality adapter supports only 480P or 768P")
    duration = int(request.get("duration") or 5)
    if duration < 4 or duration > 10:
        raise RuntimeError("quality adapter supports 4 to 10 seconds")
    if resolution == "768P" and duration > 5:
        raise RuntimeError("768P quality generation supports at most 5 seconds per segment")
    content = request.get("content")
    if not isinstance(content, list) or not content:
        raise RuntimeError("videoRequest.content is required")
    allowed_types = {"text", "image_url", "video_url", "audio_url"}
    if any(not isinstance(item, dict) or item.get("type") not in allowed_types for item in content):
        raise RuntimeError("videoRequest.content contains an unsupported item")
    request.update({
        "model": "MiniMax-H3",
        "resolution": resolution,
        "duration": duration,
        "acceleration": acceleration,
    })
    return request


def wait_runtime_nodes(timeout_seconds: int = 300) -> set[str]:
    deadline = time.monotonic() + max(1, timeout_seconds)
    last_error: Exception | None = None
    while time.monotonic() < deadline:
        try:
            return set(h3.http_json(os.environ["COMFY_URL"] + "/object_info", timeout=30))
        except (OSError, h3.AdapterError) as exc:
            last_error = exc
            time.sleep(2)
    raise RuntimeError(f"H3 quality runtime did not become ready: {last_error}")


def validate_runtime(request: dict[str, Any]) -> dict[str, Any]:
    available = wait_runtime_nodes(int(os.environ.get(
        "H3_QUALITY_RUNTIME_READY_TIMEOUT_SECONDS", "300")))
    missing = sorted(REQUIRED_NODES - available)
    if missing:
        raise RuntimeError("H3 quality runtime is missing nodes: " + ", ".join(missing))
    graph, metadata = h3.build_workflow(request)
    return {
        "ok": True,
        "mode": "validate-only",
        "nodes": len(graph),
        "resolution": metadata["resolution"],
        "duration": metadata["duration"],
        "acceleration": metadata["acceleration"],
    }


def public_result_url(url: str, comfy_url: str) -> str:
    public_base = os.environ["H3_QUALITY_RESULT_BASE"].rstrip("/")
    private_base = comfy_url.rstrip("/")
    if url.startswith(private_base + "/"):
        return public_base + url[len(private_base):]
    return url


def execute(request: dict[str, Any], timeout_seconds: int) -> dict[str, Any]:
    worker_started = time.monotonic()
    available = wait_runtime_nodes(int(os.environ.get(
        "H3_QUALITY_RUNTIME_READY_TIMEOUT_SECONDS", "900")))
    missing = sorted(REQUIRED_NODES - available)
    if missing:
        raise RuntimeError("H3 quality runtime is missing nodes: " + ", ".join(missing))
    runtime_ready_at = time.monotonic()
    graph, metadata = h3.build_workflow(request)
    marker = h3.attach_submission_marker(graph)
    comfy_url = str(metadata["comfy_url"]).rstrip("/")
    try:
        response = h3.http_json(
            comfy_url + "/prompt", method="POST",
            body={"prompt": graph, "client_id": "aid-gpu-scheduler-quality"}, timeout=60,
        )
        prompt_id = response.get("prompt_id")
    except h3.AdapterError:
        prompt_id = h3.recover_accepted_prompt_id(comfy_url, marker)
        if not prompt_id:
            raise
    if not prompt_id:
        raise RuntimeError("ComfyUI did not return prompt_id")
    generation_started = time.monotonic()
    h3.save_task(str(prompt_id), metadata)
    deadline = time.monotonic() + timeout_seconds
    while time.monotonic() < deadline:
        task = h3.task_response(str(prompt_id))["task"]
        if task["status"] in TERMINAL:
            if task["status"] != "succeeded":
                raise RuntimeError(json.dumps(task.get("error") or {}, ensure_ascii=False))
            finished_at = time.monotonic()
            return {
                "resultRef": public_result_url(task["content"]["url"], comfy_url),
                "providerTaskId": str(prompt_id),
                "duration": task["duration"],
                "resolution": task["resolution"],
                "acceleration": task["acceleration"],
                "inputImageCount": task["usage"]["input_image_count"],
                "inputSeconds": task["usage"]["input_seconds"],
                "runtimeReadyWaitSeconds": round(runtime_ready_at - worker_started, 3),
                "generationSeconds": round(finished_at - generation_started, 3),
                "workerTotalSeconds": round(finished_at - worker_started, 3),
            }
        time.sleep(5)
    raise RuntimeError("H3 quality generation exceeded worker timeout")


def main() -> None:
    parser = argparse.ArgumentParser(description="Fixed H3 quality scheduler worker")
    parser.add_argument("job_file")
    parser.add_argument("--validate-only", action="store_true")
    parser.add_argument("--timeout", type=int, default=7_000)
    args = parser.parse_args()
    request = validated_request(load_job(Path(args.job_file)))
    result = validate_runtime(request) if args.validate_only else execute(
        request, max(60, min(args.timeout, 7_100)))
    print(json.dumps(result, ensure_ascii=False, separators=(",", ":")))


if __name__ == "__main__":
    main()
