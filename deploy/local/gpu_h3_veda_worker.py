#!/usr/bin/env python3
"""Fixed low-resolution H3 Veda worker for the gpu-222 scheduler."""

from __future__ import annotations

import argparse
import json
import os
import time
from pathlib import Path
from typing import Any


os.environ.setdefault("COMFY_URL", "http://127.0.0.1:8192")
os.environ.setdefault("H3_VEDA_COMFY_URL", "http://127.0.0.1:8192")
os.environ.setdefault("H3_VEDA_ENABLED", "true")
os.environ.setdefault("H3_FAST_VSA_ENABLED", "false")
os.environ.setdefault("H3_RESOURCE_RELEASE_ENABLED", "false")
os.environ.setdefault("H3_VEDA_RESULT_BASE", "http://10.190.0.222:8192")

import comfy_h3_adapter as h3  # noqa: E402


REQUIRED_NODES = {
    "BasicGuider",
    "BasicScheduler",
    "CLIPLoader",
    "CreateVideo",
    "KSamplerSelect",
    "LoraLoaderModelOnly",
    "LTXVSeparateAVLatent",
    "MiniMaxH3ImageToVideo",
    "MiniMaxH3SigmaShift",
    "RandomNoise",
    "SamplerCustomAdvanced",
    "SaveVideo",
    "UNETLoader",
    "VAEDecodeAudio",
    "VAEDecodeTiled",
    "VAELoader",
    "VedaSparseAttention",
}
TERMINAL = {"succeeded", "failed"}


def load_job(path: Path) -> dict[str, Any]:
    if not path.is_file() or path.stat().st_size > 2 * 1024 * 1024:
        raise RuntimeError("job file is missing or exceeds 2 MiB")
    job = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(job, dict) or job.get("profile") != "h3-veda-fast":
        raise RuntimeError("job profile must be h3-veda-fast")
    return job


def validated_request(job: dict[str, Any]) -> dict[str, Any]:
    payload = job.get("payload")
    request = payload.get("videoRequest") if isinstance(payload, dict) else None
    if not isinstance(request, dict):
        raise RuntimeError("payload.videoRequest must be a JSON object")
    request = json.loads(json.dumps(request, ensure_ascii=False))
    if str(request.get("resolution") or "480P").upper() != "480P":
        raise RuntimeError("H3 Veda FAST supports only 480P")
    duration = int(request.get("duration") or 5)
    if duration < 4 or duration > 5:
        raise RuntimeError("H3 Veda FAST supports 4 to 5 seconds")
    content = request.get("content")
    if not isinstance(content, list) or not content:
        raise RuntimeError("videoRequest.content is required")
    if any(not isinstance(item, dict) or item.get("type") != "text" for item in content):
        raise RuntimeError("H3 Veda FAST is text-to-video only")
    request.update({
        "model": "MiniMax-H3",
        "resolution": "480P",
        "duration": duration,
        "acceleration": "fast_veda",
    })
    return request


def wait_runtime_nodes(timeout_seconds: int = 900) -> set[str]:
    deadline = time.monotonic() + max(1, timeout_seconds)
    last_error: Exception | None = None
    while time.monotonic() < deadline:
        try:
            return set(h3.http_json(os.environ["H3_VEDA_COMFY_URL"] + "/object_info", timeout=30))
        except (OSError, h3.AdapterError) as exc:
            last_error = exc
            time.sleep(2)
    raise RuntimeError(f"H3 Veda runtime did not become ready: {last_error}")


def validate_runtime(request: dict[str, Any]) -> dict[str, Any]:
    available = wait_runtime_nodes(int(os.environ.get(
        "H3_VEDA_RUNTIME_READY_TIMEOUT_SECONDS", "900")))
    missing = sorted(REQUIRED_NODES - available)
    if missing:
        raise RuntimeError("H3 Veda runtime is missing nodes: " + ", ".join(missing))
    graph, metadata = h3.build_workflow(request)
    return {
        "ok": True,
        "mode": "validate-only",
        "nodes": len(graph),
        "resolution": metadata["resolution"],
        "width": metadata["width"],
        "height": metadata["height"],
        "duration": metadata["duration"],
        "acceleration": metadata["acceleration"],
    }


def public_result_url(url: str, comfy_url: str) -> str:
    public_base = os.environ["H3_VEDA_RESULT_BASE"].rstrip("/")
    private_base = comfy_url.rstrip("/")
    if url.startswith(private_base + "/"):
        return public_base + url[len(private_base):]
    return url


def execute(request: dict[str, Any], timeout_seconds: int) -> dict[str, Any]:
    graph, metadata = h3.build_workflow(request)
    marker = h3.attach_submission_marker(graph)
    comfy_url = str(metadata["comfy_url"]).rstrip("/")
    try:
        response = h3.http_json(
            comfy_url + "/prompt", method="POST",
            body={"prompt": graph, "client_id": "aid-gpu-scheduler-veda"}, timeout=60,
        )
        prompt_id = response.get("prompt_id")
    except h3.AdapterError:
        prompt_id = h3.recover_accepted_prompt_id(comfy_url, marker)
        if not prompt_id:
            raise
    if not prompt_id:
        raise RuntimeError("ComfyUI did not return prompt_id")
    h3.save_task(str(prompt_id), metadata)
    deadline = time.monotonic() + timeout_seconds
    while time.monotonic() < deadline:
        task = h3.task_response(str(prompt_id))["task"]
        if task["status"] in TERMINAL:
            if task["status"] != "succeeded":
                raise RuntimeError(json.dumps(task.get("error") or {}, ensure_ascii=False))
            return {
                "resultRef": public_result_url(task["content"]["url"], comfy_url),
                "providerTaskId": str(prompt_id),
                "duration": task["duration"],
                "resolution": task["resolution"],
                "acceleration": task["acceleration"],
            }
        time.sleep(5)
    raise RuntimeError("H3 Veda generation exceeded worker timeout")


def main() -> None:
    parser = argparse.ArgumentParser(description="Fixed H3 Veda scheduler worker")
    parser.add_argument("job_file")
    parser.add_argument("--validate-only", action="store_true")
    parser.add_argument("--timeout", type=int, default=3_300)
    args = parser.parse_args()
    request = validated_request(load_job(Path(args.job_file)))
    result = validate_runtime(request) if args.validate_only else execute(
        request, max(60, min(args.timeout, 3_500)))
    print(json.dumps(result, ensure_ascii=False, separators=(",", ":")))


if __name__ == "__main__":
    main()
