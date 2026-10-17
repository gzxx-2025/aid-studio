#!/usr/bin/env python3
"""Run one scheduler-routed H3 quality acceptance and enable only on success."""

from __future__ import annotations

import argparse
import json
import os
import shutil
import subprocess
import tempfile
import time
import urllib.error
import urllib.request
from pathlib import Path

from gpu_scheduler_deploy import DeployError, validate_preflight


TERMINAL = {"SUCCEEDED", "FAILED", "CANCELLED", "LOST"}


def read_env(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        values[key.strip()] = value.strip().strip('"').strip("'")
    return values


def request_json(base_url: str, token: str, method: str, path: str,
                 body: dict | None = None) -> dict:
    data = None if body is None else json.dumps(body, ensure_ascii=False).encode()
    request = urllib.request.Request(
        base_url.rstrip("/") + path, data=data, method=method,
        headers={"Authorization": "Bearer " + token, "Content-Type": "application/json"},
    )
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            return json.load(response)
    except urllib.error.HTTPError as exc:
        detail = exc.read().decode("utf-8", "replace")[-1_000:]
        raise DeployError(f"scheduler HTTP {exc.code}: {detail}") from exc


def atomic_json(path: Path, value: dict) -> None:
    with tempfile.NamedTemporaryFile("w", dir=path.parent, delete=False, encoding="utf-8") as handle:
        temporary = Path(handle.name)
        json.dump(value, handle, ensure_ascii=False, indent=2)
        handle.write("\n")
    try:
        mode = path.stat().st_mode & 0o777 if path.exists() else 0o600
        os.chmod(temporary, mode)
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


def restart_manager() -> None:
    result = subprocess.run(
        ["/usr/bin/systemctl", "--user", "restart", "gpu-resource-manager.service"],
        text=True, capture_output=True, check=False,
    )
    if result.returncode != 0:
        raise DeployError((result.stderr or result.stdout or "manager restart failed")[-1_000:])
    for _ in range(30):
        try:
            with urllib.request.urlopen("http://127.0.0.1:18120/health", timeout=2) as response:
                if json.load(response).get("status") == "ok":
                    return
        except Exception:
            time.sleep(0.5)
    raise DeployError("manager health check failed")


def client_token(raw: str, source: str = "aid") -> str:
    for item in raw.split(","):
        name, separator, token = item.partition("=")
        if separator and name.strip() == source and token.strip():
            return token.strip()
    raise DeployError(f"client key for {source!r} is not configured")


def update_service(services: dict, **changes: object) -> None:
    service = next((item for item in services.get("services") or []
                    if item.get("id") == "h3-standard-comfyui"), None)
    if service is None:
        raise DeployError("H3 quality service inventory entry is missing")
    service.update(changes)


def set_policy(base_url: str, admin_token: str, enabled: bool) -> dict:
    return request_json(base_url, admin_token, "PATCH",
                        "/v1/admin/services/h3-standard-comfyui/policy",
                        {"enabled": enabled, "actor": "h3-quality-acceptance"})


def accept(preflight: Path, approval_token: str, home: Path,
           idempotency_key: str, timeout_seconds: int) -> dict:
    validate_preflight(preflight, approval_token)
    config = home / ".config/aid-gpu-scheduler"
    services_path = config / "services.json"
    agent_path = config / "agent.json"
    env = read_env(config / "manager.env")
    base_url = "http://127.0.0.1:18120"
    admin_token = env["GPU_MANAGER_ADMIN_TOKEN"]
    aid_token = client_token(env["GPU_MANAGER_CLIENT_KEYS"])
    agent = json.loads(agent_path.read_text(encoding="utf-8"))
    profile = (agent.get("profiles") or {}).get("h3-standard-quality")
    if not isinstance(profile, dict) or not all(profile.get(key) for key in (
        "startCommand", "stopCommand", "runCommand")):
        raise DeployError("H3 quality fixed adapter is not staged")

    stamp = time.strftime("%Y%m%d-%H%M%S", time.gmtime())
    state_root = home / ".local/state/aid-gpu-scheduler"
    acceptance_root = state_root / "acceptance" / f"h3-quality-{stamp}"
    acceptance_root.mkdir(parents=True, exist_ok=False)
    backup = acceptance_root / "services.before.json"
    shutil.copy2(services_path, backup)
    services = json.loads(services_path.read_text(encoding="utf-8"))
    job: dict = {}
    try:
        update_service(
            services, adapterReady=True, currentState="VALIDATION_PENDING",
            notes="Fixed worker staged; scheduler-routed multimodal acceptance in progress",
        )
        atomic_json(services_path, services)
        restart_manager()
        set_policy(base_url, admin_token, True)
        job = request_json(base_url, aid_token, "POST", "/v1/jobs", {
            "profile": "h3-standard-quality",
            "idempotencyKey": idempotency_key,
            "payload": {"videoRequest": {
                "model": "MiniMax-H3", "duration": 4, "resolution": "480P",
                "ratio": "16:9", "acceleration": "turbo",
                "content": [
                    {"type": "text", "text": (
                        "Use the two supplied images as visual references. Preserve coherent "
                        "subjects and composition, with subtle natural motion and a steady cinematic camera."
                    )},
                    {"type": "image_url", "role": "reference_image",
                     "image_url": {"url": "http://127.0.0.1:8188/view?filename=fs_024.png&type=input"}},
                    {"type": "image_url", "role": "reference_image",
                     "image_url": {"url": "http://127.0.0.1:8188/view?filename=fs_023.png&type=input"}},
                ],
            }},
        })
        print(json.dumps({"jobId": job.get("id"), "status": job.get("status")}), flush=True)
        set_policy(base_url, admin_token, False)
        deadline = time.monotonic() + timeout_seconds
        last_status = None
        while time.monotonic() < deadline:
            job = request_json(base_url, aid_token, "GET", f"/v1/jobs/{int(job['id'])}")
            status = str(job.get("status") or "").upper()
            if status != last_status:
                print(json.dumps({"jobId": job.get("id"), "status": status}), flush=True)
                last_status = status
            if status in TERMINAL:
                break
            time.sleep(5)
        else:
            raise DeployError("H3 quality acceptance timed out")
        if str(job.get("status")).upper() != "SUCCEEDED":
            raise DeployError(f"H3 quality acceptance failed: {job.get('error_message') or job.get('error')}")

        services = json.loads(services_path.read_text(encoding="utf-8"))
        result = job.get("result") if isinstance(job.get("result"), dict) else {}
        update_service(
            services, adapterReady=True, currentState="READY_VALIDATED",
            recommendedAction="已通过调度器多参考图 Turbo 480P/4秒真实生成验收",
            notes=f"scheduler job={job.get('id')}; result={result.get('resultRef')}",
        )
        atomic_json(services_path, services)
        restart_manager()
        set_policy(base_url, admin_token, True)
        manifest = {
            "profile": "h3-standard-quality", "status": "succeeded",
            "jobId": job.get("id"), "result": result, "createdAt": time.time(),
            "servicesBackup": str(backup),
        }
        atomic_json(acceptance_root / "manifest.json", manifest)
        return manifest
    except Exception:
        try:
            shutil.copy2(backup, services_path)
            restart_manager()
            set_policy(base_url, admin_token, False)
        finally:
            atomic_json(acceptance_root / "failed.json", {
                "profile": "h3-standard-quality", "status": "failed",
                "jobId": job.get("id"), "createdAt": time.time(),
                "servicesBackup": str(backup),
            })
        raise


def main() -> None:
    parser = argparse.ArgumentParser(description="Accept H3 quality scheduler adapter")
    parser.add_argument("--preflight", required=True)
    parser.add_argument("--approval-token", required=True)
    parser.add_argument("--home", default=str(Path.home()))
    parser.add_argument("--idempotency-key", default="h3-quality-multiref-acceptance-v1")
    parser.add_argument("--timeout", type=int, default=7_200)
    args = parser.parse_args()
    try:
        result = accept(
            Path(args.preflight).resolve(), args.approval_token, Path(args.home).resolve(),
            args.idempotency_key, max(60, min(args.timeout, 7_200)),
        )
        print(json.dumps(result, ensure_ascii=False), flush=True)
    except (DeployError, OSError, ValueError, KeyError, json.JSONDecodeError) as exc:
        print(json.dumps({"status": "failed", "error": str(exc)}, ensure_ascii=False), flush=True)
        raise SystemExit(2)


if __name__ == "__main__":
    main()
