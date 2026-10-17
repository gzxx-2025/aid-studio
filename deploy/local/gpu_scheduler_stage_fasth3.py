#!/usr/bin/env python3
"""Stage the FastH3 fixed adapter without enabling GPU job execution."""

from __future__ import annotations

import argparse
import json
import os
import shutil
import subprocess
import tempfile
import time
from pathlib import Path

from gpu_scheduler_deploy import DeployError, validate_preflight


ADAPTER_FILES = ("gpu_fasth3_worker.py", "comfy_h3_adapter.py", "h3_i2v_api.json")


def run(command: list[str], timeout: int = 60) -> str:
    result = subprocess.run(command, text=True, capture_output=True, check=False, timeout=timeout)
    if result.returncode != 0:
        raise DeployError((result.stderr or result.stdout or "command failed")[-2_000:])
    return result.stdout


def atomic_copy(source: Path, target: Path) -> None:
    target.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(dir=target.parent, delete=False) as handle:
        temporary = Path(handle.name)
    try:
        shutil.copy2(source, temporary)
        os.replace(temporary, target)
    finally:
        temporary.unlink(missing_ok=True)


def stage_fasth3(source_root: Path, preflight: Path, token: str, home: Path,
                 python_bin: str) -> Path:
    validate_preflight(preflight, token)
    agent_path = home / ".config/aid-gpu-scheduler/agent.json"
    install_root = home / ".local/share/aid-gpu-scheduler/adapters/fasth3"
    state_root = home / ".local/state/aid-gpu-scheduler"
    agent = json.loads(agent_path.read_text(encoding="utf-8"))
    if agent.get("executionMode") != "observe":
        raise DeployError("FastH3 may only be staged while the Agent is in observe mode")
    for name in (*ADAPTER_FILES, "fasth3-validate-job.json"):
        if not (source_root / name).is_file():
            raise DeployError(f"missing FastH3 adapter source: {name}")
    run([
        python_bin, str(source_root / "gpu_fasth3_worker.py"),
        str(source_root / "fasth3-validate-job.json"), "--validate-only",
    ], timeout=120)

    stamp = time.strftime("%Y%m%d-%H%M%S", time.gmtime())
    backup_root = state_root / "backups" / f"fasth3-stage-{stamp}"
    backup_root.mkdir(parents=True, exist_ok=False)
    records = []
    targets = [agent_path, *(install_root / name for name in ADAPTER_FILES)]
    for target in targets:
        record = {"path": str(target), "existed": target.exists(), "backup": None}
        if target.exists():
            backup = backup_root / target.relative_to(home)
            backup.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(target, backup)
            record["backup"] = str(backup)
        records.append(record)

    try:
        for name in ADAPTER_FILES:
            atomic_copy(source_root / name, install_root / name)
        worker = install_root / "gpu_fasth3_worker.py"
        worker.chmod(0o755)
        agent.setdefault("profiles", {})["h3-single-fast"] = {
            "startCommand": [
                "/usr/bin/systemctl", "--user", "start", "comfyui-fasth3.service",
            ],
            "stopCommand": [
                "/usr/bin/systemctl", "--user", "stop", "comfyui-fasth3.service",
            ],
            "runCommand": [python_bin, str(worker), "{job_file}"],
            "resultRequired": True,
            "startTimeoutSeconds": 900,
            "stopTimeoutSeconds": 180,
            "allowedProcessPatterns": [
                r"comfyui-fasth3-shadow/.*/python .*main\.py.*--port 8191",
            ],
        }
        agent["idleInfrastructure"] = [
            {
                "profile": "h3-standard-quality", "maxIdleVramMb": 512,
                "allowedProcessPatterns": [r"/home/neo/ComfyUI/main\.py.*--port 8188"],
            },
            {
                "profile": "h3-single-fast", "maxIdleVramMb": 512,
                "allowedProcessPatterns": [r"comfyui-fasth3-shadow/ComfyUI/main\.py.*--port 8191"],
            },
            {
                "profile": "h3-veda-fast", "maxIdleVramMb": 512,
                "allowedProcessPatterns": [r"comfyui-h3-veda-039/ComfyUI/main\.py.*--port 8192"],
            },
            {
                "profile": "qianchuan-asr", "maxIdleVramMb": 3072,
                "allowedProcessPatterns": [r"qianchuan-story-analyzer/asr-venv/bin/python.*story_analyzer_service:app"],
            },
        ]
        agent_path.write_text(json.dumps(agent, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        agent_path.chmod(0o600)
        run(["/usr/bin/systemctl", "--user", "restart", "gpu-resource-agent.service"])
    except Exception:
        for record in records:
            target = Path(record["path"])
            if record["existed"]:
                shutil.copy2(record["backup"], target)
            else:
                target.unlink(missing_ok=True)
        run(["/usr/bin/systemctl", "--user", "restart", "gpu-resource-agent.service"])
        raise

    manifest = backup_root / "manifest.json"
    manifest.write_text(json.dumps({
        "scope": "user", "stage": "fasth3-adapter-staged-observe-only",
        "createdAt": time.time(), "approvalToken": token, "files": records,
    }, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return manifest


def main() -> None:
    parser = argparse.ArgumentParser(description="Stage FastH3 adapter in observe mode")
    parser.add_argument("--source-root", required=True)
    parser.add_argument("--preflight", required=True)
    parser.add_argument("--approval-token", required=True)
    parser.add_argument("--home", default=str(Path.home()))
    parser.add_argument(
        "--python-bin", default="/home/neo/comfyui-fasth3-shadow/env-cu130/bin/python")
    args = parser.parse_args()
    try:
        manifest = stage_fasth3(
            Path(args.source_root).resolve(), Path(args.preflight).resolve(),
            args.approval_token, Path(args.home).resolve(), args.python_bin,
        )
        print(json.dumps({
            "staged": True, "enabled": False, "executionMode": "observe",
            "rollbackManifest": str(manifest),
        }, ensure_ascii=False))
    except (DeployError, OSError, ValueError, json.JSONDecodeError) as exc:
        print(json.dumps({"staged": False, "error": str(exc)}, ensure_ascii=False))
        raise SystemExit(2)


if __name__ == "__main__":
    main()
