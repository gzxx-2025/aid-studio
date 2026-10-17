#!/usr/bin/env python3
"""Adopt the idle FastH3 listener into its verified user systemd service."""

from __future__ import annotations

import argparse
import json
import os
import re
import signal
import subprocess
import time
import urllib.request
from pathlib import Path

from gpu_scheduler_deploy import DeployError, validate_preflight


PORT = 8191
UNIT = "comfyui-fasth3.service"


def run(command: list[str], check: bool = True) -> subprocess.CompletedProcess[str]:
    result = subprocess.run(command, text=True, capture_output=True, check=False)
    if check and result.returncode != 0:
        raise DeployError((result.stderr or result.stdout or "command failed")[-2_000:])
    return result


def queue_idle() -> bool:
    with urllib.request.urlopen(f"http://127.0.0.1:{PORT}/queue", timeout=5) as response:
        value = json.load(response)
    return not value.get("queue_running") and not value.get("queue_pending")


def health_ready(attempts: int = 120) -> bool:
    for _ in range(attempts):
        try:
            with urllib.request.urlopen(
                f"http://127.0.0.1:{PORT}/system_stats", timeout=3
            ) as response:
                return response.status == 200
        except Exception:
            time.sleep(1)
    return False


def listener_pid() -> int | None:
    output = run(["ss", "-lntp"], check=True).stdout
    line = next((item for item in output.splitlines() if f":{PORT} " in item), "")
    match = re.search(r"pid=(\d+)", line)
    return int(match.group(1)) if match else None


def expected_fasth3_argv(argv: list[str]) -> bool:
    joined = " ".join(argv)
    has_port = any(
        value == "--port" and index + 1 < len(argv) and argv[index + 1] == str(PORT)
        for index, value in enumerate(argv)
    )
    return (
        bool(argv)
        and "comfyui-fasth3-shadow" in joined
        and any(value == "main.py" or value.endswith("/main.py") for value in argv)
        and has_port
    )


def process_snapshot(pid: int) -> tuple[list[str], dict[str, str], str]:
    proc = Path("/proc") / str(pid)
    argv = [item.decode("utf-8", "surrogateescape") for item in (proc / "cmdline").read_bytes().split(b"\0") if item]
    if not expected_fasth3_argv(argv):
        raise DeployError("port 8191 listener is not the expected FastH3 process")
    environment = {}
    for item in (proc / "environ").read_bytes().split(b"\0"):
        if b"=" in item:
            key, value = item.split(b"=", 1)
            environment[key.decode("utf-8", "surrogateescape")] = value.decode("utf-8", "surrogateescape")
    cwd = os.readlink(proc / "cwd")
    if Path(cwd).resolve() != Path("/home/neo/comfyui-fasth3-shadow/ComfyUI"):
        raise DeployError("FastH3 listener has an unexpected working directory")
    return argv, environment, cwd


def wait_exit(pid: int, seconds: int) -> bool:
    for _ in range(seconds * 5):
        if not (Path("/proc") / str(pid)).exists():
            return True
        time.sleep(0.2)
    return False


def adopt(preflight: Path, token: str, state_root: Path) -> Path:
    validate_preflight(preflight, token)
    if not queue_idle():
        raise DeployError("FastH3 queue is no longer idle")
    old_pid = listener_pid()
    if old_pid is None:
        raise DeployError("port 8191 has no listener to adopt")
    argv, environment, cwd = process_snapshot(old_pid)
    verify = run([
        "systemd-analyze", "--user", "verify",
        str(Path.home() / ".config/systemd/user" / UNIT),
    ], check=False)
    if verify.returncode != 0:
        raise DeployError("FastH3 systemd unit failed verification")

    stamp = time.strftime("%Y%m%d-%H%M%S", time.gmtime())
    record_root = state_root / "adoptions" / f"fasth3-{stamp}"
    record_root.mkdir(parents=True, exist_ok=False)
    manifest = record_root / "manifest.json"
    rollback_used = False
    new_pid = None
    try:
        os.kill(old_pid, signal.SIGINT)
        if not wait_exit(old_pid, 30):
            os.kill(old_pid, signal.SIGTERM)
            if not wait_exit(old_pid, 15):
                raise DeployError("FastH3 process did not stop after SIGINT and SIGTERM")
        run(["systemctl", "--user", "enable", "--now", UNIT])
        if not health_ready():
            raise DeployError("managed FastH3 service did not become healthy")
        new_pid = int(run(["systemctl", "--user", "show", UNIT, "-p", "MainPID", "--value"]).stdout.strip())
        if new_pid <= 1 or new_pid == old_pid:
            raise DeployError("managed FastH3 service returned an invalid MainPID")
    except Exception:
        rollback_used = True
        run(["systemctl", "--user", "stop", UNIT], check=False)
        log = (record_root / "rollback-fast-h3.log").open("ab")
        subprocess.Popen(argv, cwd=cwd, env=environment, stdout=log, stderr=log, start_new_session=True)
        if not health_ready(60):
            raise DeployError("systemd adoption failed and legacy FastH3 rollback did not recover")
        raise
    finally:
        manifest.write_text(json.dumps({
            "stage": "fasth3-systemd-adoption", "createdAt": time.time(),
            "oldPid": old_pid, "newPid": new_pid, "rollbackUsed": rollback_used,
            "unit": UNIT, "port": PORT,
        }, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return manifest


def main() -> None:
    parser = argparse.ArgumentParser(description="Adopt FastH3 listener into systemd")
    parser.add_argument("--preflight", required=True)
    parser.add_argument("--approval-token", required=True)
    parser.add_argument(
        "--state-root", default=str(Path.home() / ".local/state/aid-gpu-scheduler"))
    args = parser.parse_args()
    try:
        manifest = adopt(
            Path(args.preflight).resolve(), args.approval_token,
            Path(args.state_root).resolve())
        print(json.dumps({"adopted": True, "manifest": str(manifest)}, ensure_ascii=False))
    except (DeployError, OSError, ValueError) as exc:
        print(json.dumps({"adopted": False, "error": str(exc)}, ensure_ascii=False))
        raise SystemExit(2)


if __name__ == "__main__":
    main()
