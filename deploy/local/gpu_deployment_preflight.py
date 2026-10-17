#!/usr/bin/env python3
"""Read-only deployment gate for the GPU resource manager on gpu-222.

Run this script on the GPU node immediately before requesting administrator
approval.  It never stops services or changes GPU state.  Unknown queue state is
blocking: an operator must resolve it or explicitly defer that integration.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import subprocess
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


COMFY_SERVICES = (
    (8188, "comfyui.service", False),
    (8191, "comfyui-fasth3.service", True),
    (8192, "comfyui-veda.service", True),
)
CELERY_CHECKS = (
    ("qianchuan-video-stack", "video-generate-v2-celery_worker-1", "app.workers.celery_app"),
    ("legacy-video-generate", "video-generate-celery_worker-1", "app.workers.celery_app"),
    ("avcs-gpu-worker", "avcs-celery-gpu0", "app.celery_app"),
)


def run(command: list[str], timeout: int = 10) -> subprocess.CompletedProcess[str]:
    return subprocess.run(command, text=True, capture_output=True, check=False, timeout=timeout)


def service_state(unit: str, user_service: bool) -> str:
    command = ["systemctl"]
    if user_service:
        command.append("--user")
    command.extend(["is-active", unit])
    result = run(command)
    return (result.stdout or result.stderr).strip().lower()


def comfy_queue(port: int, unit: str, user_service: bool) -> dict[str, Any]:
    try:
        with urllib.request.urlopen(f"http://127.0.0.1:{port}/queue", timeout=5) as response:
            body = json.load(response)
        running = len(body.get("queue_running") or [])
        pending = len(body.get("queue_pending") or [])
        return {"port": port, "status": "OK", "running": running, "pending": pending}
    except (OSError, ValueError, urllib.error.URLError) as exc:
        state = service_state(unit, user_service)
        if state == "inactive":
            return {
                "port": port, "unit": unit, "status": "STOPPED_IDLE",
                "running": 0, "pending": 0,
            }
        return {"port": port, "status": "UNKNOWN", "running": None, "pending": None,
                "unit": unit, "serviceState": state,
                "detail": f"{type(exc).__name__}: {exc}"[:300]}


def parse_celery_output(service_id: str, output: str, returncode: int) -> dict[str, Any]:
    compact = " ".join(line.strip() for line in output.splitlines() if line.strip())
    if returncode == 0 and "- empty -" in output:
        return {"serviceId": service_id, "status": "EMPTY", "active": 0}
    if returncode == 0 and "->" in output and compact:
        return {"serviceId": service_id, "status": "ACTIVE", "active": 1,
                "detail": compact[:500]}
    detail = compact[-500:] if compact else f"inspect exited {returncode} without output"
    return {"serviceId": service_id, "status": "UNKNOWN", "active": None, "detail": detail}


def celery_queue(service_id: str, container: str, app: str) -> dict[str, Any]:
    try:
        result = run([
            "docker", "exec", container, "celery", "-A", app,
            "inspect", "active", "--timeout", "3",
        ], timeout=8)
        return parse_celery_output(service_id, result.stdout + "\n" + result.stderr, result.returncode)
    except (OSError, subprocess.TimeoutExpired) as exc:
        return {"serviceId": service_id, "status": "UNKNOWN", "active": None,
                "detail": f"{type(exc).__name__}: {exc}"[:300]}


def gpu_state(samples: int, interval: float) -> dict[str, Any]:
    peak: dict[int, float] = {}
    last: list[dict[str, Any]] = []
    for index in range(samples):
        result = run([
            "nvidia-smi", "--query-gpu=index,uuid,utilization.gpu,memory.used,memory.total,power.draw",
            "--format=csv,noheader,nounits",
        ])
        if result.returncode != 0:
            return {"status": "UNKNOWN", "detail": (result.stderr or result.stdout)[-500:]}
        current: list[dict[str, Any]] = []
        for line in result.stdout.splitlines():
            gpu_index, uuid, utilization, used, total, power = [value.strip() for value in line.split(",", 5)]
            item = {
                "index": int(gpu_index), "uuid": uuid, "utilizationPercent": float(utilization),
                "memoryUsedMiB": float(used), "memoryTotalMiB": float(total), "powerW": float(power),
            }
            current.append(item)
            peak[item["index"]] = max(peak.get(item["index"], 0), item["utilizationPercent"])
        last = current
        if index + 1 < samples:
            time.sleep(interval)
    return {"status": "OK", "gpus": last, "peakUtilizationPercent": peak}


def gpu_processes() -> list[dict[str, Any]]:
    result = run([
        "nvidia-smi", "--query-compute-apps=gpu_uuid,pid,process_name,used_gpu_memory",
        "--format=csv,noheader,nounits",
    ])
    processes: list[dict[str, Any]] = []
    if result.returncode != 0:
        return processes
    for line in result.stdout.splitlines():
        if not line.strip():
            continue
        uuid, pid, name, memory = [value.strip() for value in line.split(",", 3)]
        processes.append({"gpuUuid": uuid, "pid": int(pid), "processName": name,
                          "usedVramMiB": float(memory) if memory not in {"N/A", "[N/A]"} else None})
    return processes


def build_report(samples: int = 3, interval: float = 2.0,
                 excluded_services: set[str] | None = None) -> dict[str, Any]:
    excluded_services = excluded_services or set()
    generated = time.time()
    comfy = [comfy_queue(*service) for service in COMFY_SERVICES]
    celery = [celery_queue(*item) for item in CELERY_CHECKS]
    gpu = gpu_state(samples, interval)
    blockers: list[str] = []
    for queue in comfy:
        if queue["status"] not in {"OK", "STOPPED_IDLE"}:
            blockers.append(f"ComfyUI {queue['port']} 队列状态未知")
        elif (queue["running"] or 0) > 0 or (queue["pending"] or 0) > 0:
            blockers.append(f"ComfyUI {queue['port']} 有运行或排队任务")
    for queue in celery:
        queue["excludedFromDeployment"] = queue["serviceId"] in excluded_services
        if queue["excludedFromDeployment"]:
            continue
        if queue["status"] == "UNKNOWN":
            blockers.append(f"{queue['serviceId']} Celery状态未知")
        elif queue["status"] == "ACTIVE":
            blockers.append(f"{queue['serviceId']} 有活跃任务")
    if gpu.get("status") != "OK":
        blockers.append("GPU遥测不可用")
    elif any(float(value) >= 10 for value in gpu.get("peakUtilizationPercent", {}).values()):
        blockers.append("采样期间GPU利用率达到10%以上")
    report = {
        "schemaVersion": "aid-gpu-deployment-preflight/v1",
        "generatedAt": generated,
        "generatedAtIso": datetime.fromtimestamp(generated, timezone.utc).isoformat(),
        "validUntil": generated + 300,
        "safeToRequestApproval": not blockers,
        "blockers": blockers,
        "excludedServices": sorted(excluded_services),
        "comfyQueues": comfy,
        "celeryQueues": celery,
        "gpu": gpu,
        "gpuProcesses": gpu_processes(),
        "changesApplied": False,
    }
    canonical = json.dumps(report, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    report["approvalToken"] = hashlib.sha256(canonical.encode("utf-8")).hexdigest()[:16]
    return report


def main() -> None:
    parser = argparse.ArgumentParser(description="Read-only GPU scheduler deployment preflight")
    parser.add_argument("--samples", type=int, default=3)
    parser.add_argument("--interval", type=float, default=2.0)
    parser.add_argument("--output")
    parser.add_argument(
        "--exclude-service", action="append", default=[],
        choices=[item[0] for item in CELERY_CHECKS],
        help="Explicitly leave an unhealthy service outside this deployment stage; repeatable",
    )
    args = parser.parse_args()
    report = build_report(
        max(1, min(args.samples, 10)), max(0.1, min(args.interval, 10)), set(args.exclude_service),
    )
    payload = json.dumps(report, ensure_ascii=False, indent=2)
    if args.output:
        Path(args.output).write_text(payload + "\n", encoding="utf-8")
    print(payload)
    raise SystemExit(0 if report["safeToRequestApproval"] else 3)


if __name__ == "__main__":
    main()
