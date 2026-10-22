#!/usr/bin/env python3
"""Approval-gated stage-one installer for the GPU scheduler.

Default operation is plan-only.  Apply requires a fresh safe preflight report,
its exact approval token, root, and an explicit --apply flag.  Stage one binds
the manager to loopback and runs the Agent in observe mode; it does not stop or
restart existing GPU workloads.
"""

from __future__ import annotations

import argparse
import json
import os
import secrets
import shutil
import subprocess
import time
from pathlib import Path
from typing import Any


class DeployError(RuntimeError):
    pass


FILES = {
    "gpu_resource_manager.py": "/opt/aid-gpu-scheduler/gpu_resource_manager.py",
    "gpu_resource_agent.py": "/opt/aid-gpu-scheduler/gpu_resource_agent.py",
    "gpu_profile_control.py": "/opt/aid-gpu-scheduler/gpu_profile_control.py",
    "gpu_deployment_preflight.py": "/opt/aid-gpu-scheduler/gpu_deployment_preflight.py",
    "gpu-resource-profiles.gpu-222.json": "/etc/aid-gpu-profiles.json",
    "gpu-resource-services.gpu-222.json": "/etc/aid-gpu-services.json",
    "gpu-profile-control.gpu-222.json": "/etc/aid-gpu-profile-control.json",
}


def validate_preflight(path: Path, token: str, now: float | None = None) -> dict[str, Any]:
    report = json.loads(path.read_text(encoding="utf-8"))
    current = time.time() if now is None else now
    if report.get("schemaVersion") != "aid-gpu-deployment-preflight/v1":
        raise DeployError("unsupported preflight report")
    if report.get("changesApplied") is not False:
        raise DeployError("preflight report is not read-only")
    if not report.get("safeToRequestApproval"):
        raise DeployError("preflight has blocking conditions")
    if float(report.get("validUntil") or 0) < current:
        raise DeployError("preflight report expired; rerun it before approval")
    if not token or not secrets.compare_digest(str(report.get("approvalToken") or ""), token):
        raise DeployError("approval token does not match the fresh preflight report")
    return report


def run(command: list[str]) -> None:
    result = subprocess.run(command, text=True, capture_output=True, check=False)
    if result.returncode != 0:
        raise DeployError((result.stderr or result.stdout or "command failed")[-2000:])


def ensure_system_user(name: str, extra_groups: list[str] | None = None) -> None:
    exists = subprocess.run(["/usr/bin/id", name], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode == 0
    if not exists:
        run(["/usr/sbin/useradd", "--system", "--home", "/nonexistent", "--shell", "/usr/sbin/nologin", name])
    if extra_groups:
        available = [group for group in extra_groups if subprocess.run(
            ["/usr/bin/getent", "group", group], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL
        ).returncode == 0]
        if available:
            run(["/usr/sbin/usermod", "-aG", ",".join(available), name])


def unit_text(kind: str) -> str:
    if kind == "manager":
        command = (
            "/usr/bin/python3 /opt/aid-gpu-scheduler/gpu_resource_manager.py "
            "--host 127.0.0.1 --port 18120 --db /var/lib/aid-gpu-manager/gpu-manager.db "
            "--profiles /etc/aid-gpu-profiles.json --services /etc/aid-gpu-services.json"
        )
        description = "AID GPU Resource Manager (stage-one loopback control plane)"
        env_file = "/etc/aid-gpu-manager.env"
        user = "aid-gpu-manager"
        writable = "/var/lib/aid-gpu-manager"
    else:
        command = "/usr/bin/python3 /opt/aid-gpu-scheduler/gpu_resource_agent.py --config /etc/aid-gpu-agent.json"
        description = "AID GPU Resource Agent (stage-one observe mode)"
        env_file = None
        user = "aid-gpu-agent"
        writable = "/var/lib/aid-gpu-agent"
    lines = [
        "[Unit]", f"Description={description}", "After=network-online.target", "Wants=network-online.target", "",
        "[Service]", "Type=simple", f"User={user}", f"Group={user}",
    ]
    if env_file:
        lines.append(f"EnvironmentFile={env_file}")
    if kind == "agent":
        lines.extend([
            "AmbientCapabilities=CAP_SYS_PTRACE CAP_KILL",
            "CapabilityBoundingSet=CAP_SYS_PTRACE CAP_KILL",
            "SupplementaryGroups=video render",
        ])
    lines.extend([
        "WorkingDirectory=/opt/aid-gpu-scheduler", f"ExecStart={command}",
        "Restart=on-failure", "RestartSec=3", "NoNewPrivileges=true", "PrivateTmp=true",
        "ProtectSystem=strict", f"ReadWritePaths={writable}", "", "[Install]",
        "WantedBy=multi-user.target", "",
    ])
    return "\n".join(lines)


def backup_file(path: Path, backup_root: Path, manifest: list[dict[str, Any]]) -> None:
    item = {"path": str(path), "existed": path.exists(), "backup": None}
    if path.exists():
        backup = backup_root / path.as_posix().lstrip("/")
        backup.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(path, backup)
        item["backup"] = str(backup)
    manifest.append(item)


def install(source_root: Path, preflight: Path, approval_token: str) -> Path:
    validate_preflight(preflight, approval_token)
    if os.geteuid() != 0:
        raise DeployError("--apply requires root")
    stamp = time.strftime("%Y%m%d-%H%M%S", time.gmtime())
    backup_root = Path("/var/backups/aid-gpu-scheduler") / stamp
    backup_root.mkdir(parents=True, exist_ok=False)
    manifest: list[dict[str, Any]] = []
    targets = [Path(value) for value in FILES.values()] + [
        Path("/etc/aid-gpu-manager.env"), Path("/etc/aid-gpu-agent.json"),
        Path("/etc/systemd/system/gpu-resource-manager.service"),
        Path("/etc/systemd/system/gpu-resource-agent.service"),
    ]
    for target in targets:
        backup_file(target, backup_root, manifest)
    ensure_system_user("aid-gpu-manager")
    ensure_system_user("aid-gpu-agent", ["video", "render"])
    Path("/opt/aid-gpu-scheduler").mkdir(parents=True, exist_ok=True)
    Path("/var/lib/aid-gpu-manager").mkdir(parents=True, exist_ok=True)
    Path("/var/lib/aid-gpu-agent").mkdir(parents=True, exist_ok=True)
    shutil.chown("/var/lib/aid-gpu-manager", user="aid-gpu-manager", group="aid-gpu-manager")
    shutil.chown("/var/lib/aid-gpu-agent", user="aid-gpu-agent", group="aid-gpu-agent")
    for source_name, target_name in FILES.items():
        target = Path(target_name)
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source_root / source_name, target)
        target.chmod(0o755 if target.suffix == ".py" else 0o644)
    agent_token = secrets.token_urlsafe(48)
    admin_token = secrets.token_urlsafe(48)
    client_token = secrets.token_urlsafe(48)
    env_path = Path("/etc/aid-gpu-manager.env")
    env_path.write_text(
        f"GPU_MANAGER_CLIENT_KEYS=aid={client_token}\n"
        f"GPU_MANAGER_AGENT_TOKEN={agent_token}\n"
        f"GPU_MANAGER_ADMIN_TOKEN={admin_token}\n",
        encoding="utf-8",
    )
    env_path.chmod(0o600)
    agent_config = {
        "managerUrl": "http://127.0.0.1:18120", "agentToken": agent_token,
        "nodeId": "gpu-222", "hostname": "gpuserver", "pollSeconds": 2,
        "executionMode": "observe", "terminationGraceSeconds": 5,
        "spoolDir": "/var/lib/aid-gpu-agent/jobs", "profiles": {},
    }
    agent_path = Path("/etc/aid-gpu-agent.json")
    agent_path.write_text(json.dumps(agent_config, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    agent_path.chmod(0o600)
    shutil.chown(agent_path, user="aid-gpu-agent", group="aid-gpu-agent")
    Path("/etc/systemd/system/gpu-resource-manager.service").write_text(unit_text("manager"), encoding="utf-8")
    Path("/etc/systemd/system/gpu-resource-agent.service").write_text(unit_text("agent"), encoding="utf-8")
    manifest_path = backup_root / "manifest.json"
    manifest_path.write_text(json.dumps({
        "createdAt": time.time(), "preflightApprovalToken": approval_token,
        "files": manifest, "stage": "observe-only",
    }, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    run(["/usr/bin/systemctl", "daemon-reload"])
    run(["/usr/bin/systemctl", "enable", "--now", "gpu-resource-manager.service"])
    run(["/usr/bin/systemctl", "enable", "--now", "gpu-resource-agent.service"])
    return manifest_path


def plan(source_root: Path) -> dict[str, Any]:
    return {
        "stage": "observe-only",
        "changesApplied": False,
        "sourceRoot": str(source_root),
        "files": [{"source": str(source_root / source), "target": target} for source, target in FILES.items()],
        "services": ["gpu-resource-manager.service", "gpu-resource-agent.service"],
        "managerBind": "127.0.0.1:18120",
        "agentMode": "observe",
        "existingGpuServicesStopped": [],
        "profilesEnabled": [],
    }


def main() -> None:
    parser = argparse.ArgumentParser(description="Approval-gated GPU scheduler stage-one installer")
    parser.add_argument("--source-root", default=str(Path(__file__).resolve().parent))
    parser.add_argument("--preflight")
    parser.add_argument("--approval-token")
    parser.add_argument("--apply", action="store_true")
    args = parser.parse_args()
    source_root = Path(args.source_root).resolve()
    try:
        if not args.apply:
            print(json.dumps(plan(source_root), ensure_ascii=False, indent=2))
            return
        if not args.preflight:
            raise DeployError("--apply requires --preflight")
        manifest = install(source_root, Path(args.preflight), str(args.approval_token or ""))
        print(json.dumps({"installed": True, "stage": "observe-only", "rollbackManifest": str(manifest)}, ensure_ascii=False))
    except DeployError as exc:
        print(json.dumps({"installed": False, "error": str(exc)}, ensure_ascii=False))
        raise SystemExit(2)


if __name__ == "__main__":
    main()
