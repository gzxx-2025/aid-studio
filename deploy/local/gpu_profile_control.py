#!/usr/bin/env python3
"""Root-owned fixed service lifecycle controller for the GPU node Agent.

The profile name selects a predeclared controller.  No client payload or shell
fragment is accepted.  Stop refuses while a configured queue reports work.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import socket
import stat
import subprocess
import time
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any


NAME_RE = re.compile(r"^[A-Za-z0-9_.@-]+$")


class ControlError(RuntimeError):
    pass


def run(command: list[str], timeout: int = 120) -> subprocess.CompletedProcess[str]:
    return subprocess.run(command, text=True, capture_output=True, check=False, timeout=timeout)


def load_config(path: Path, require_root_owner: bool = True) -> dict[str, Any]:
    info = path.stat()
    if require_root_owner and (info.st_uid != 0 or info.st_mode & (stat.S_IWGRP | stat.S_IWOTH)):
        raise ControlError("controller config must be root-owned and not group/world writable")
    data = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(data.get("profiles"), dict):
        raise ControlError("controller config requires a profiles object")
    return data


def user_systemctl(user: str, action: str, units: list[str], uid: int) -> list[str]:
    return [
        "/usr/sbin/runuser", "-u", user, "--", "/usr/bin/env",
        f"XDG_RUNTIME_DIR=/run/user/{uid}",
        f"DBUS_SESSION_BUS_ADDRESS=unix:path=/run/user/{uid}/bus",
        "/usr/bin/systemctl", "--user", action, *units,
    ]


def controller_command(controller: dict[str, Any], action: str) -> list[str]:
    kind = str(controller.get("type") or "")
    units = [str(value) for value in controller.get("units") or []]
    if not units or any(not NAME_RE.fullmatch(value) for value in units):
        raise ControlError("controller units are missing or invalid")
    if kind == "systemd-system":
        return ["/usr/bin/systemctl", action, *units]
    if kind == "systemd-user":
        user = str(controller.get("user") or "")
        uid = int(controller.get("uid"))
        if not NAME_RE.fullmatch(user):
            raise ControlError("invalid systemd user")
        return user_systemctl(user, action, units, uid)
    if kind == "docker-compose":
        compose_file = Path(str(controller.get("composeFile") or ""))
        if not compose_file.is_absolute():
            raise ControlError("composeFile must be absolute")
        if action == "start":
            return ["/usr/bin/docker", "compose", "-f", str(compose_file), "up", "-d", *units]
        if action == "stop":
            return ["/usr/bin/docker", "compose", "-f", str(compose_file), "stop", *units]
        if action == "is-active":
            return ["/usr/bin/docker", "compose", "-f", str(compose_file), "ps", "--status", "running", *units]
    raise ControlError(f"unsupported controller type/action: {kind}/{action}")


def queue_is_idle(check: dict[str, Any]) -> tuple[bool, str]:
    kind = str(check.get("type") or "")
    url = str(check.get("url") or "")
    if kind == "comfy-queue":
        try:
            with urllib.request.urlopen(url, timeout=5) as response:
                body = json.load(response)
        except (OSError, ValueError, urllib.error.URLError) as exc:
            return False, f"queue check unavailable: {type(exc).__name__}: {exc}"
        running = len(body.get("queue_running") or [])
        pending = len(body.get("queue_pending") or [])
        return running == 0 and pending == 0, f"running={running}, pending={pending}"
    if kind == "json-fields-idle":
        try:
            with urllib.request.urlopen(url, timeout=5) as response:
                body = json.load(response)
        except (OSError, ValueError, urllib.error.URLError) as exc:
            return False, f"queue check unavailable: {type(exc).__name__}: {exc}"
        expected = check.get("expected") or {}
        mismatches = {key: body.get(key) for key, value in expected.items() if body.get(key) != value}
        return not mismatches, "idle" if not mismatches else f"busy fields={mismatches}"
    if kind == "llama-slots":
        key_file = Path(str(check.get("apiKeyFile") or ""))
        if not key_file.is_absolute():
            raise ControlError("llama slot check requires an absolute apiKeyFile")
        try:
            token = next(line.strip() for line in key_file.read_text(encoding="utf-8").splitlines() if line.strip())
            request = urllib.request.Request(url, headers={"Authorization": f"Bearer {token}"})
            with urllib.request.urlopen(request, timeout=5) as response:
                slots = json.load(response)
        except (OSError, StopIteration, ValueError, urllib.error.URLError) as exc:
            return False, f"slot check unavailable: {type(exc).__name__}: {exc}"
        busy = [slot.get("id") for slot in slots if slot.get("is_processing")]
        return not busy, "idle" if not busy else f"busy slots={busy}"
    raise ControlError(f"unsupported pre-stop check: {kind}")


def wait_health(urls: list[str], timeout_seconds: int) -> None:
    deadline = time.monotonic() + timeout_seconds
    pending = set(urls)
    while pending and time.monotonic() < deadline:
        for url in list(pending):
            try:
                with urllib.request.urlopen(url, timeout=3) as response:
                    if 200 <= response.status < 300:
                        pending.remove(url)
            except (OSError, urllib.error.URLError):
                pass
        if pending:
            time.sleep(1)
    if pending:
        raise ControlError(f"health checks did not become ready: {sorted(pending)}")


def wait_tcp(endpoints: list[str], timeout_seconds: int) -> None:
    deadline = time.monotonic() + timeout_seconds
    pending = set(endpoints)
    while pending and time.monotonic() < deadline:
        for endpoint in list(pending):
            host, raw_port = endpoint.rsplit(":", 1)
            try:
                with socket.create_connection((host, int(raw_port)), timeout=3):
                    pending.remove(endpoint)
            except OSError:
                pass
        if pending:
            time.sleep(1)
    if pending:
        raise ControlError(f"TCP health checks did not become ready: {sorted(pending)}")


def control(config: dict[str, Any], profile_name: str, action: str,
            allow_changes: bool = True) -> dict[str, Any]:
    profile = config["profiles"].get(profile_name)
    if not isinstance(profile, dict):
        raise ControlError("profile is not allow-listed")
    controller = profile.get("controller") or {}
    if action == "stop":
        for check in profile.get("preStopChecks") or []:
            idle, detail = queue_is_idle(check)
            if not idle:
                raise ControlError(f"refusing to stop busy or unknown service: {detail}")
    system_action = "is-active" if action == "status" else action
    command = controller_command(controller, system_action)
    if not allow_changes and action in {"start", "stop"}:
        return {"profile": profile_name, "action": action, "plannedCommand": command, "changed": False}
    result = run(command, timeout=int(profile.get("timeoutSeconds", 180)))
    if action == "status":
        return {
            "profile": profile_name, "action": action,
            "active": result.returncode == 0 and bool(result.stdout.strip()),
            "detail": (result.stdout or result.stderr).strip()[-1000:],
            "changed": False,
        }
    if result.returncode != 0:
        raise ControlError((result.stderr or result.stdout or "service control failed")[-1000:])
    if action == "start":
        health_timeout = int(profile.get("healthTimeoutSeconds", 180))
        wait_health([str(value) for value in profile.get("healthUrls") or []], health_timeout)
        wait_tcp([str(value) for value in profile.get("healthTcp") or []], health_timeout)
    return {"profile": profile_name, "action": action, "changed": True}


def main() -> None:
    parser = argparse.ArgumentParser(description="Fixed GPU profile service controller")
    parser.add_argument("action", choices=("start", "stop", "status"))
    parser.add_argument("profile")
    parser.add_argument("--config", default="/etc/aid-gpu-profile-control.json")
    parser.add_argument("--plan", action="store_true")
    args = parser.parse_args()
    if args.action in {"start", "stop"} and not args.plan and os.geteuid() != 0:
        raise SystemExit("start/stop requires root")
    try:
        config = load_config(Path(args.config), require_root_owner=not args.plan)
        print(json.dumps(control(config, args.profile, args.action, allow_changes=not args.plan),
                         ensure_ascii=False))
    except ControlError as exc:
        print(json.dumps({"error": str(exc)}, ensure_ascii=False))
        raise SystemExit(2)


if __name__ == "__main__":
    main()
