#!/usr/bin/env python3
"""Approval-gated activation of the validated FastH3 scheduler adapter."""

from __future__ import annotations

import argparse
import json
import os
import shutil
import subprocess
import tempfile
import time
import urllib.request
from pathlib import Path

from gpu_scheduler_deploy import DeployError, validate_preflight


def run(command: list[str]) -> None:
    result = subprocess.run(command, text=True, capture_output=True, check=False)
    if result.returncode != 0:
        raise DeployError((result.stderr or result.stdout or "command failed")[-2_000:])


def atomic_json(path: Path, value: dict) -> None:
    with tempfile.NamedTemporaryFile("w", dir=path.parent, delete=False, encoding="utf-8") as handle:
        temporary = Path(handle.name)
        json.dump(value, handle, ensure_ascii=False, indent=2)
        handle.write("\n")
    try:
        os.chmod(temporary, path.stat().st_mode & 0o777)
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


def health_ok(url: str = "http://127.0.0.1:18120/health", attempts: int = 30) -> bool:
    for _ in range(attempts):
        try:
            with urllib.request.urlopen(url, timeout=2) as response:
                return json.load(response).get("status") == "ok"
        except Exception:
            time.sleep(0.5)
    return False


def validate_acceptance(path: Path) -> dict:
    data = json.loads(path.read_text(encoding="utf-8"))
    if data.get("profile") != "h3-single-fast" or data.get("status") != "succeeded":
        raise DeployError("FastH3 acceptance manifest is not successful")
    output = Path(str(data.get("outputPath") or ""))
    if not output.is_absolute() or not output.is_file() or output.stat().st_size <= 0:
        raise DeployError("FastH3 acceptance output is missing")
    if data.get("resolution") != "480P" or int(data.get("duration") or 0) != 5:
        raise DeployError("FastH3 acceptance settings do not match the production adapter")
    return data


def activate(preflight: Path, token: str, acceptance: Path, home: Path) -> Path:
    validate_preflight(preflight, token)
    accepted = validate_acceptance(acceptance)
    config_root = home / ".config/aid-gpu-scheduler"
    state_root = home / ".local/state/aid-gpu-scheduler"
    agent_path = config_root / "agent.json"
    services_path = config_root / "services.json"
    agent = json.loads(agent_path.read_text(encoding="utf-8"))
    services = json.loads(services_path.read_text(encoding="utf-8"))
    profile = (agent.get("profiles") or {}).get("h3-single-fast")
    if not isinstance(profile, dict) or not profile.get("runCommand"):
        raise DeployError("FastH3 fixed adapter is not staged on this node")
    if not profile.get("startCommand") or not profile.get("stopCommand"):
        raise DeployError("FastH3 lifecycle commands are missing")
    service = next((item for item in services.get("services") or []
                    if item.get("id") == "h3-fasth3-unmanaged"), None)
    if service is None:
        raise DeployError("FastH3 service inventory entry is missing")

    stamp = time.strftime("%Y%m%d-%H%M%S", time.gmtime())
    backup_root = state_root / "backups" / f"fasth3-activate-{stamp}"
    backup_root.mkdir(parents=True, exist_ok=False)
    records = []
    for path in (agent_path, services_path):
        backup = backup_root / path.relative_to(home)
        backup.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(path, backup)
        records.append({"path": str(path), "backup": str(backup)})

    agent["executionMode"] = "enforce"
    agent["idleInfrastructure"] = [
        item for item in agent.get("idleInfrastructure") or []
        if item.get("profile") != "qwen38-local-llm"
    ]
    service.update({
        "adapterReady": True,
        "currentState": "READY_VALIDATED",
        "currentVramMiB": 256,
        "recommendedAction": "已通过真实5秒480P生成验收，可由统一GPU队列启用",
        "notes": (
            f"FastH3 scheduler acceptance passed; output={accepted['outputPath']}; "
            f"elapsedSeconds={accepted.get('elapsedSeconds')}"
        ),
    })
    manifest = backup_root / "manifest.json"
    manifest.write_text(json.dumps({
        "stage": "fasth3-adapter-activated",
        "createdAt": time.time(),
        "approvalToken": token,
        "acceptanceManifest": str(acceptance),
        "files": records,
    }, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    try:
        atomic_json(agent_path, agent)
        agent_path.chmod(0o600)
        atomic_json(services_path, services)
        run(["/usr/bin/systemctl", "--user", "restart", "gpu-resource-manager.service"])
        if not health_ok():
            raise DeployError("manager health check failed after FastH3 activation")
        run(["/usr/bin/systemctl", "--user", "restart", "gpu-resource-agent.service"])
    except Exception:
        for record in records:
            shutil.copy2(record["backup"], record["path"])
        run(["/usr/bin/systemctl", "--user", "restart", "gpu-resource-manager.service"])
        run(["/usr/bin/systemctl", "--user", "restart", "gpu-resource-agent.service"])
        raise
    return manifest


def main() -> None:
    parser = argparse.ArgumentParser(description="Activate validated FastH3 scheduler adapter")
    parser.add_argument("--preflight", required=True)
    parser.add_argument("--approval-token", required=True)
    parser.add_argument("--acceptance-manifest", required=True)
    parser.add_argument("--home", default=str(Path.home()))
    args = parser.parse_args()
    try:
        manifest = activate(
            Path(args.preflight).resolve(), args.approval_token,
            Path(args.acceptance_manifest).resolve(), Path(args.home).resolve(),
        )
        print(json.dumps({
            "activated": True,
            "profile": "h3-single-fast",
            "policyEnabled": False,
            "rollbackManifest": str(manifest),
        }, ensure_ascii=False))
    except (DeployError, OSError, ValueError, json.JSONDecodeError) as exc:
        print(json.dumps({"activated": False, "error": str(exc)}, ensure_ascii=False))
        raise SystemExit(2)


if __name__ == "__main__":
    main()
