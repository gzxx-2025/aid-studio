#!/usr/bin/env python3
"""Rollback a user-scope GPU scheduler observe deployment."""

from __future__ import annotations

import argparse
import json
import shutil
import subprocess
from pathlib import Path


def rollback(manifest_path: Path) -> None:
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    if manifest.get("scope") != "user":
        raise SystemExit("not a user-scope rollback manifest")
    subprocess.run(["/usr/bin/systemctl", "--user", "disable", "--now", "gpu-resource-agent.service"], check=False)
    subprocess.run(["/usr/bin/systemctl", "--user", "disable", "--now", "gpu-resource-manager.service"], check=False)
    for item in reversed(manifest.get("files") or []):
        target = Path(item["path"])
        if item.get("existed"):
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(Path(item["backup"]), target)
        elif target.exists() and (target.is_file() or target.is_symlink()):
            target.unlink()
    subprocess.run(["/usr/bin/systemctl", "--user", "daemon-reload"], check=False)
    print(json.dumps({"rolledBack": True, "manifest": str(manifest_path)}, ensure_ascii=False))


def main() -> None:
    parser = argparse.ArgumentParser(description="Rollback user-scope GPU scheduler")
    parser.add_argument("manifest")
    args = parser.parse_args()
    rollback(Path(args.manifest).resolve())


if __name__ == "__main__":
    main()
