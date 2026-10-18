#!/usr/bin/env python3
"""Stage the isolated H3 runtime A/B profile with policy disabled."""

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


PROFILE_ID = "h3-runtime-ab"
SERVICE_ID = "h3-runtime-ab-benchmark"


def run(command: list[str], timeout: int = 120) -> str:
    result = subprocess.run(command, text=True, capture_output=True, check=False, timeout=timeout)
    if result.returncode != 0:
        raise DeployError((result.stderr or result.stdout or "command failed")[-2_000:])
    return result.stdout


def atomic_json(path: Path, value: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile("w", dir=path.parent, delete=False, encoding="utf-8") as handle:
        temporary = Path(handle.name)
        json.dump(value, handle, ensure_ascii=False, indent=2)
        handle.write("\n")
    try:
        if path.exists():
            os.chmod(temporary, path.stat().st_mode & 0o777)
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


def copy_atomic(source: Path, target: Path, executable: bool = False) -> None:
    target.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(dir=target.parent, delete=False) as handle:
        temporary = Path(handle.name)
    try:
        shutil.copy2(source, temporary)
        if executable:
            temporary.chmod(0o755)
        os.replace(temporary, target)
    finally:
        temporary.unlink(missing_ok=True)


def health_ok(attempts: int = 30) -> bool:
    for _ in range(attempts):
        try:
            with urllib.request.urlopen("http://127.0.0.1:18120/health", timeout=2) as response:
                if json.load(response).get("status") == "ok":
                    return True
        except Exception:
            time.sleep(0.5)
    return False


def stage(source_root: Path, preflight: Path, token: str, home: Path) -> Path:
    validate_preflight(preflight, token)
    config = home / ".config/aid-gpu-scheduler"
    install = home / ".local/share/aid-gpu-scheduler/adapters/h3-runtime-ab"
    state = home / ".local/state/aid-gpu-scheduler"
    unit_root = home / ".config/systemd/user"
    lab = Path("/mnt/disk2/h3-runtime-ab")
    sources = {
        "worker": source_root / "gpu_h3_runtime_ab_worker.py",
        "benchmark": source_root / "h3-shadow/run_runtime_ab_benchmark.py",
        "unit": source_root / "h3-shadow/comfyui-h3-runtime-ab.service",
        "profiles": source_root / "gpu-resource-profiles.gpu-222.json",
        "services": source_root / "gpu-resource-services.gpu-222.json",
    }
    if any(not path.is_file() for path in sources.values()):
        raise DeployError("runtime A/B stage sources are incomplete")

    paths = {
        "agent": config / "agent.json",
        "profiles": config / "profiles.json",
        "services": config / "services.json",
        "worker": install / "gpu_h3_runtime_ab_worker.py",
        "benchmark": lab / "run_runtime_ab_benchmark.py",
        "unit": unit_root / "comfyui-h3-runtime-ab.service",
    }
    stamp = time.strftime("%Y%m%d-%H%M%S", time.gmtime())
    backup_root = state / "backups" / f"h3-runtime-ab-stage-{stamp}"
    backup_root.mkdir(parents=True, exist_ok=False)
    records = []
    for path in paths.values():
        record = {"path": str(path), "existed": path.exists(), "backup": None}
        if path.exists():
            backup = backup_root / str(path).lstrip("/")
            backup.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(path, backup)
            record["backup"] = str(backup)
        records.append(record)

    try:
        profiles = json.loads(paths["profiles"].read_text(encoding="utf-8"))
        services = json.loads(paths["services"].read_text(encoding="utf-8"))
        agent = json.loads(paths["agent"].read_text(encoding="utf-8"))
        source_profiles = json.loads(sources["profiles"].read_text(encoding="utf-8"))
        source_services = json.loads(sources["services"].read_text(encoding="utf-8"))
        profiles[PROFILE_ID] = source_profiles[PROFILE_ID]
        services["services"] = [
            item for item in services.get("services", []) if item.get("id") != SERVICE_ID
        ]
        services["services"].append(next(
            item for item in source_services["services"] if item.get("id") == SERVICE_ID
        ))
        copy_atomic(sources["worker"], paths["worker"], executable=True)
        copy_atomic(sources["benchmark"], paths["benchmark"], executable=True)
        copy_atomic(sources["unit"], paths["unit"])
        agent.setdefault("profiles", {})[PROFILE_ID] = {
            "startCommand": ["/usr/bin/systemctl", "--user", "start", "comfyui-h3-runtime-ab.service"],
            "stopCommand": ["/usr/bin/systemctl", "--user", "stop", "comfyui-h3-runtime-ab.service"],
            "runCommand": [
                "/home/neo/comfyui-fasth3-shadow/env-cu130/bin/python",
                str(paths["worker"]), "{job_file}",
            ],
            "resultRequired": True,
            "startTimeoutSeconds": 60,
            "stopTimeoutSeconds": 180,
            "allowedProcessPatterns": [r"/home/neo/ComfyUI/main\.py.*--port 8194"],
        }
        atomic_json(paths["profiles"], profiles)
        atomic_json(paths["services"], services)
        atomic_json(paths["agent"], agent)
        paths["agent"].chmod(0o600)
        run(["/usr/bin/systemctl", "--user", "daemon-reload"])
        run(["/usr/bin/systemctl", "--user", "restart", "gpu-resource-manager.service"])
        if not health_ok():
            raise DeployError("manager health check failed")
        run(["/usr/bin/systemctl", "--user", "restart", "gpu-resource-agent.service"])
    except Exception:
        for record in records:
            target = Path(record["path"])
            if record["existed"]:
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(record["backup"], target)
            else:
                target.unlink(missing_ok=True)
        run(["/usr/bin/systemctl", "--user", "daemon-reload"])
        run(["/usr/bin/systemctl", "--user", "restart", "gpu-resource-manager.service"])
        run(["/usr/bin/systemctl", "--user", "restart", "gpu-resource-agent.service"])
        raise

    manifest = backup_root / "manifest.json"
    manifest.write_text(json.dumps({
        "stage": "h3-runtime-ab-disabled", "createdAt": time.time(),
        "approvalToken": token, "files": records,
    }, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return manifest


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source-root", required=True)
    parser.add_argument("--preflight", required=True)
    parser.add_argument("--approval-token", required=True)
    parser.add_argument("--home", default=str(Path.home()))
    args = parser.parse_args()
    try:
        manifest = stage(
            Path(args.source_root).resolve(), Path(args.preflight).resolve(),
            args.approval_token, Path(args.home).resolve(),
        )
        print(json.dumps({
            "staged": True, "profile": PROFILE_ID, "policyEnabled": False,
            "rollbackManifest": str(manifest),
        }, ensure_ascii=False))
    except (DeployError, OSError, ValueError, KeyError, json.JSONDecodeError) as exc:
        print(json.dumps({"staged": False, "error": str(exc)}, ensure_ascii=False))
        raise SystemExit(2)


if __name__ == "__main__":
    main()
