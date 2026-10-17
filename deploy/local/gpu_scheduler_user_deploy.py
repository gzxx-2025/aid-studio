#!/usr/bin/env python3
"""Approval-gated user-scope observe deployment for gpu-222."""

from __future__ import annotations

import argparse
import json
import secrets
import shutil
import subprocess
import time
from pathlib import Path

from gpu_scheduler_deploy import DeployError, validate_preflight


SOURCE_FILES = (
    "gpu_resource_manager.py", "gpu_resource_agent.py", "gpu_profile_control.py",
    "gpu_deployment_preflight.py", "gpu-resource-profiles.gpu-222.json",
    "gpu-resource-services.gpu-222.json", "gpu-profile-control.gpu-222.json",
)


def run(command: list[str]) -> None:
    result = subprocess.run(command, text=True, capture_output=True, check=False)
    if result.returncode != 0:
        raise DeployError((result.stderr or result.stdout or "command failed")[-2000:])


def user_unit(kind: str, install_root: Path, config_root: Path, state_root: Path) -> str:
    if kind == "manager":
        description = "AID GPU Resource Manager (user-scope observe stage)"
        command = (
            f"/usr/bin/python3 {install_root}/gpu_resource_manager.py --host 127.0.0.1 --port 18120 "
            f"--db {state_root}/manager/gpu-manager.db --profiles {config_root}/profiles.json "
            f"--services {config_root}/services.json"
        )
        env = f"EnvironmentFile={config_root}/manager.env\n"
    else:
        description = "AID GPU Resource Agent (user-scope observe only)"
        command = f"/usr/bin/python3 {install_root}/gpu_resource_agent.py --config {config_root}/agent.json"
        env = ""
    return (
        "[Unit]\n"
        f"Description={description}\n"
        "After=network-online.target\n"
        "Wants=network-online.target\n\n"
        "[Service]\n"
        "Type=simple\n"
        f"{env}"
        f"WorkingDirectory={install_root}\n"
        f"ExecStart={command}\n"
        "Restart=on-failure\n"
        "RestartSec=3\n"
        "NoNewPrivileges=true\n"
        "PrivateTmp=true\n\n"
        "[Install]\n"
        "WantedBy=default.target\n"
    )


def install_user(source_root: Path, preflight: Path, token: str, home: Path) -> Path:
    validate_preflight(preflight, token)
    install_root = home / ".local/share/aid-gpu-scheduler"
    config_root = home / ".config/aid-gpu-scheduler"
    state_root = home / ".local/state/aid-gpu-scheduler"
    unit_root = home / ".config/systemd/user"
    stamp = time.strftime("%Y%m%d-%H%M%S", time.gmtime())
    backup_root = state_root / "backups" / stamp
    backup_root.mkdir(parents=True, exist_ok=False)
    targets = [install_root / name for name in SOURCE_FILES] + [
        config_root / "profiles.json", config_root / "services.json", config_root / "profile-control.json",
        config_root / "manager.env", config_root / "agent.json",
        unit_root / "gpu-resource-manager.service", unit_root / "gpu-resource-agent.service",
    ]
    manifest_files = []
    for target in targets:
        record = {"path": str(target), "existed": target.exists(), "backup": None}
        if target.exists():
            backup = backup_root / target.relative_to(home)
            backup.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(target, backup)
            record["backup"] = str(backup)
        manifest_files.append(record)
    install_root.mkdir(parents=True, exist_ok=True)
    config_root.mkdir(parents=True, exist_ok=True)
    (state_root / "manager").mkdir(parents=True, exist_ok=True)
    (state_root / "agent/jobs").mkdir(parents=True, exist_ok=True)
    unit_root.mkdir(parents=True, exist_ok=True)
    for name in SOURCE_FILES:
        shutil.copy2(source_root / name, install_root / name)
    shutil.copy2(source_root / "gpu-resource-profiles.gpu-222.json", config_root / "profiles.json")
    shutil.copy2(source_root / "gpu-resource-services.gpu-222.json", config_root / "services.json")
    shutil.copy2(source_root / "gpu-profile-control.gpu-222.json", config_root / "profile-control.json")
    agent_token = secrets.token_urlsafe(48)
    manager_env = (
        f"GPU_MANAGER_CLIENT_KEYS=aid={secrets.token_urlsafe(48)}\n"
        f"GPU_MANAGER_AGENT_TOKEN={agent_token}\n"
        f"GPU_MANAGER_ADMIN_TOKEN={secrets.token_urlsafe(48)}\n"
    )
    (config_root / "manager.env").write_text(manager_env, encoding="utf-8")
    (config_root / "manager.env").chmod(0o600)
    agent = {
        "managerUrl": "http://127.0.0.1:18120", "agentToken": agent_token,
        "nodeId": "gpu-222", "hostname": "gpuserver", "pollSeconds": 2,
        "executionMode": "observe", "terminationGraceSeconds": 5,
        "spoolDir": str(state_root / "agent/jobs"), "profiles": {},
    }
    (config_root / "agent.json").write_text(json.dumps(agent, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (config_root / "agent.json").chmod(0o600)
    (unit_root / "gpu-resource-manager.service").write_text(
        user_unit("manager", install_root, config_root, state_root), encoding="utf-8")
    (unit_root / "gpu-resource-agent.service").write_text(
        user_unit("agent", install_root, config_root, state_root), encoding="utf-8")
    manifest = backup_root / "manifest.json"
    manifest.write_text(json.dumps({
        "scope": "user", "stage": "observe-only", "createdAt": time.time(),
        "approvalToken": token, "home": str(home), "files": manifest_files,
    }, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    run(["/usr/bin/systemctl", "--user", "daemon-reload"])
    run(["/usr/bin/systemctl", "--user", "enable", "--now", "gpu-resource-manager.service"])
    run(["/usr/bin/systemctl", "--user", "enable", "--now", "gpu-resource-agent.service"])
    return manifest


def main() -> None:
    parser = argparse.ArgumentParser(description="User-scope observe-only GPU scheduler installer")
    parser.add_argument("--source-root", required=True)
    parser.add_argument("--preflight", required=True)
    parser.add_argument("--approval-token", required=True)
    parser.add_argument("--home", default=str(Path.home()))
    args = parser.parse_args()
    try:
        manifest = install_user(
            Path(args.source_root).resolve(), Path(args.preflight).resolve(),
            args.approval_token, Path(args.home).resolve(),
        )
        print(json.dumps({"installed": True, "scope": "user", "stage": "observe-only",
                          "rollbackManifest": str(manifest)}, ensure_ascii=False))
    except DeployError as exc:
        print(json.dumps({"installed": False, "error": str(exc)}, ensure_ascii=False))
        raise SystemExit(2)


if __name__ == "__main__":
    main()
