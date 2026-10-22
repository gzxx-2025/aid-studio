#!/usr/bin/env python3
"""Stage the fixed H3 quality adapter while leaving its policy disabled."""

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


ADAPTER_FILES = (
    "gpu_h3_quality_worker.py",
    "gpu_h3_quality_runtime.py",
    "gpu_scheduler_client.py",
    "comfy_h3_adapter.py",
    "h3_i2v_api.json",
)


def run(command: list[str], timeout: int = 120) -> str:
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


def stage(source_root: Path, preflight: Path, token: str, home: Path,
          python_bin: str) -> Path:
    validate_preflight(preflight, token)
    agent_path = home / ".config/aid-gpu-scheduler/agent.json"
    install_root = home / ".local/share/aid-gpu-scheduler/adapters/h3-quality"
    state_root = home / ".local/state/aid-gpu-scheduler"
    agent = json.loads(agent_path.read_text(encoding="utf-8"))
    for name in (*ADAPTER_FILES, "h3-quality-validate-job.json"):
        if not (source_root / name).is_file():
            raise DeployError(f"missing H3 quality adapter source: {name}")
    runtime = source_root / "gpu_h3_quality_runtime.py"
    run([python_bin, str(runtime), "health"], timeout=60)
    run([
        python_bin, str(source_root / "gpu_h3_quality_worker.py"),
        str(source_root / "h3-quality-validate-job.json"), "--validate-only",
    ], timeout=180)

    stamp = time.strftime("%Y%m%d-%H%M%S", time.gmtime())
    backup_root = state_root / "backups" / f"h3-quality-stage-{stamp}"
    backup_root.mkdir(parents=True, exist_ok=False)
    records = []
    for target in (agent_path, *(install_root / name for name in ADAPTER_FILES)):
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
        worker = install_root / "gpu_h3_quality_worker.py"
        lifecycle = install_root / "gpu_h3_quality_runtime.py"
        worker.chmod(0o755)
        lifecycle.chmod(0o755)
        agent.setdefault("profiles", {})["h3-standard-quality"] = {
            "startCommand": [python_bin, str(lifecycle), "health"],
            "stopCommand": [python_bin, str(lifecycle), "release"],
            "runCommand": [python_bin, str(worker), "{job_file}"],
            "resultRequired": True,
            "startTimeoutSeconds": 60,
            "stopTimeoutSeconds": 180,
            "allowedProcessPatterns": [r"/home/neo/ComfyUI/main\.py.*--port 8188"],
        }
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
        "stage": "h3-quality-adapter-staged-policy-disabled",
        "createdAt": time.time(), "approvalToken": token, "files": records,
    }, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return manifest


def main() -> None:
    parser = argparse.ArgumentParser(description="Stage H3 quality scheduler adapter")
    parser.add_argument("--source-root", required=True)
    parser.add_argument("--preflight", required=True)
    parser.add_argument("--approval-token", required=True)
    parser.add_argument("--home", default=str(Path.home()))
    parser.add_argument("--python-bin", default="/home/neo/miniconda3/envs/comfyui/bin/python")
    args = parser.parse_args()
    try:
        manifest = stage(
            Path(args.source_root).resolve(), Path(args.preflight).resolve(),
            args.approval_token, Path(args.home).resolve(), args.python_bin,
        )
        print(json.dumps({
            "staged": True, "profile": "h3-standard-quality",
            "policyEnabled": False, "rollbackManifest": str(manifest),
        }, ensure_ascii=False))
    except (DeployError, OSError, ValueError, json.JSONDecodeError) as exc:
        print(json.dumps({"staged": False, "error": str(exc)}, ensure_ascii=False))
        raise SystemExit(2)


if __name__ == "__main__":
    main()
