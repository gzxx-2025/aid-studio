#!/usr/bin/env python3
"""Approval-gated control-plane update for an existing user-scope deployment."""

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


def health_ok(url: str = "http://127.0.0.1:18120/health", attempts: int = 20) -> bool:
    for _ in range(attempts):
        try:
            with urllib.request.urlopen(url, timeout=2) as response:
                return json.load(response).get("status") == "ok"
        except Exception:
            time.sleep(0.5)
    return False


def update_user(source_root: Path, preflight: Path, token: str, home: Path) -> Path:
    validate_preflight(preflight, token)
    install_root = home / ".local/share/aid-gpu-scheduler"
    config_root = home / ".config/aid-gpu-scheduler"
    state_root = home / ".local/state/aid-gpu-scheduler"
    targets = {
        "gpu_resource_manager.py": install_root / "gpu_resource_manager.py",
        "gpu_resource_agent.py": install_root / "gpu_resource_agent.py",
        "gpu-resource-profiles.gpu-222.json": config_root / "profiles.json",
        "gpu-resource-services.gpu-222.json": config_root / "services.json",
    }
    for name, target in targets.items():
        if not (source_root / name).is_file() or not target.is_file():
            raise DeployError(f"missing update source or installed target: {name}")

    stamp = time.strftime("%Y%m%d-%H%M%S", time.gmtime())
    backup_root = state_root / "backups" / f"control-update-{stamp}"
    backup_root.mkdir(parents=True, exist_ok=False)
    records = []
    for name, target in targets.items():
        backup = backup_root / target.relative_to(home)
        backup.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(target, backup)
        records.append({"path": str(target), "backup": str(backup)})

    manifest = backup_root / "manifest.json"
    manifest.write_text(json.dumps({
        "scope": "user", "stage": "control-plane-safety-update",
        "createdAt": time.time(), "approvalToken": token, "files": records,
    }, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    try:
        for name, target in targets.items():
            target.parent.mkdir(parents=True, exist_ok=True)
            with tempfile.NamedTemporaryFile(dir=target.parent, delete=False) as handle:
                temporary = Path(handle.name)
            try:
                shutil.copy2(source_root / name, temporary)
                os.replace(temporary, target)
            finally:
                temporary.unlink(missing_ok=True)
        run(["/usr/bin/systemctl", "--user", "restart", "gpu-resource-manager.service"])
        if not health_ok():
            raise DeployError("manager health check failed after update")
        run(["/usr/bin/systemctl", "--user", "restart", "gpu-resource-agent.service"])
    except Exception:
        for record in records:
            shutil.copy2(record["backup"], record["path"])
        run(["/usr/bin/systemctl", "--user", "restart", "gpu-resource-manager.service"])
        run(["/usr/bin/systemctl", "--user", "restart", "gpu-resource-agent.service"])
        raise
    return manifest


def main() -> None:
    parser = argparse.ArgumentParser(description="Update user-scope GPU scheduler control plane")
    parser.add_argument("--source-root", required=True)
    parser.add_argument("--preflight", required=True)
    parser.add_argument("--approval-token", required=True)
    parser.add_argument("--home", default=str(Path.home()))
    args = parser.parse_args()
    try:
        manifest = update_user(
            Path(args.source_root).resolve(), Path(args.preflight).resolve(),
            args.approval_token, Path(args.home).resolve(),
        )
        print(json.dumps({"updated": True, "rollbackManifest": str(manifest)}, ensure_ascii=False))
    except DeployError as exc:
        print(json.dumps({"updated": False, "error": str(exc)}, ensure_ascii=False))
        raise SystemExit(2)


if __name__ == "__main__":
    main()
