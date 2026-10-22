#!/usr/bin/env python3
"""Restore files recorded by a gpu_scheduler_deploy.py manifest."""

from __future__ import annotations

import argparse
import json
import os
import shutil
import subprocess
from pathlib import Path


def run(command: list[str]) -> None:
    subprocess.run(command, check=False, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def rollback(manifest_path: Path) -> None:
    if os.geteuid() != 0:
        raise SystemExit("rollback requires root")
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    run(["/usr/bin/systemctl", "disable", "--now", "gpu-resource-agent.service"])
    run(["/usr/bin/systemctl", "disable", "--now", "gpu-resource-manager.service"])
    for item in reversed(manifest.get("files") or []):
        target = Path(item["path"])
        if item.get("existed"):
            backup = Path(item["backup"])
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(backup, target)
        elif target.exists() and (target.is_file() or target.is_symlink()):
            target.unlink()
    run(["/usr/bin/systemctl", "daemon-reload"])
    print(json.dumps({"rolledBack": True, "manifest": str(manifest_path)}, ensure_ascii=False))


def main() -> None:
    parser = argparse.ArgumentParser(description="Rollback GPU scheduler stage-one installation")
    parser.add_argument("manifest")
    args = parser.parse_args()
    rollback(Path(args.manifest).resolve())


if __name__ == "__main__":
    main()
