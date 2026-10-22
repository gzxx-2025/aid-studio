#!/usr/bin/env python3
"""Fixed lifecycle checks for the scheduler-managed H3 quality runtime."""

from __future__ import annotations

import argparse
import json
import os
import urllib.request


COMFY_URL = os.getenv("H3_QUALITY_COMFY_URL", "http://127.0.0.1:8188").rstrip("/")


def request_json(path: str, *, body: dict | None = None) -> dict:
    data = None if body is None else json.dumps(body).encode()
    request = urllib.request.Request(
        COMFY_URL + path, data=data, method="POST" if body is not None else "GET",
        headers={"Content-Type": "application/json"},
    )
    with urllib.request.urlopen(request, timeout=30) as response:
        raw = response.read()
    return json.loads(raw) if raw else {}


def queue_idle() -> dict:
    queue = request_json("/queue")
    running = len(queue.get("queue_running") or [])
    pending = len(queue.get("queue_pending") or [])
    if running or pending:
        raise RuntimeError(f"H3 quality runtime is busy: running={running}, pending={pending}")
    return {"running": running, "pending": pending}


def health() -> dict:
    queue = queue_idle()
    stats = request_json("/system_stats")
    return {"ok": True, "action": "health", "queue": queue,
            "comfyVersion": (stats.get("system") or {}).get("comfyui_version")}


def release() -> dict:
    queue = queue_idle()
    request_json("/free", body={"unload_models": True, "free_memory": True})
    return {"ok": True, "action": "release", "queue": queue}


def main() -> None:
    parser = argparse.ArgumentParser(description="H3 quality runtime lifecycle helper")
    parser.add_argument("action", choices=("health", "release"))
    args = parser.parse_args()
    result = health() if args.action == "health" else release()
    print(json.dumps(result, ensure_ascii=False, separators=(",", ":")))


if __name__ == "__main__":
    main()
