#!/usr/bin/env python3
"""Small dependency-free client for the AID GPU resource manager.

GPU workloads are submitted as allow-listed profiles.  This client never
opens GPU devices, SSH sessions, Docker sockets, or private worker ports.
"""

from __future__ import annotations

import json
import os
import ssl
import time
import urllib.error
import urllib.request
from dataclasses import dataclass
from typing import Any, Mapping
from urllib.parse import urlparse
from pathlib import Path


TERMINAL_STATES = {"SUCCEEDED", "FAILED", "CANCELLED", "LOST"}


@dataclass(frozen=True)
class SchedulerApiError(RuntimeError):
    status: int
    code: str
    message: str

    def __str__(self) -> str:
        return f"{self.code}: {self.message}"


class GpuSchedulerClient:
    def __init__(self, base_url: str, api_key: str, timeout_seconds: float = 15.0,
                 ca_file: str | None = None):
        parsed = urlparse(base_url)
        if parsed.scheme not in {"http", "https"} or not parsed.netloc:
            raise ValueError("base_url must be an absolute HTTP(S) URL")
        if not api_key.strip():
            raise ValueError("api_key is required")
        self.base_url = base_url.rstrip("/")
        self._api_key = api_key.strip()
        self.timeout_seconds = timeout_seconds
        self._ssl_context = ssl.create_default_context(cafile=ca_file) if ca_file else None

    @classmethod
    def from_environment(cls) -> "GpuSchedulerClient":
        base_url = os.environ.get("AID_GPU_MANAGER_URL", "").strip()
        api_key = os.environ.get("AID_GPU_MANAGER_API_KEY", "").strip()
        api_key_file = os.environ.get("AID_GPU_MANAGER_API_KEY_FILE", "").strip()
        ca_file = os.environ.get("AID_GPU_MANAGER_CA_FILE", "").strip() or None
        if not api_key and api_key_file:
            api_key = Path(api_key_file).read_text(encoding="utf-8").strip()
        if not base_url or not api_key:
            raise RuntimeError(
                "AID_GPU_MANAGER_URL and AID_GPU_MANAGER_API_KEY (or its _FILE form) must be configured"
            )
        return cls(base_url, api_key, ca_file=ca_file)

    def _request(self, method: str, path: str, body: Mapping[str, Any] | None = None) -> Any:
        raw = None if body is None else json.dumps(body, ensure_ascii=False).encode("utf-8")
        request = urllib.request.Request(
            self.base_url + path,
            data=raw,
            method=method,
            headers={
                "Authorization": f"Bearer {self._api_key}",
                "Accept": "application/json",
                "Content-Type": "application/json",
                "User-Agent": "aid-gpu-scheduler-client/1",
            },
        )
        try:
            with urllib.request.urlopen(
                request, timeout=self.timeout_seconds, context=self._ssl_context
            ) as response:
                return json.load(response)
        except urllib.error.HTTPError as exc:
            try:
                try:
                    payload = json.load(exc)
                except (json.JSONDecodeError, UnicodeDecodeError):
                    payload = {}
            finally:
                exc.close()
            error = payload.get("error") if isinstance(payload, dict) else None
            code = str((error or {}).get("code") or "HTTP_ERROR")
            message = str((error or {}).get("message") or exc.reason)
            raise SchedulerApiError(exc.code, code, message) from exc
        except urllib.error.URLError as exc:
            raise SchedulerApiError(0, "SCHEDULER_UNAVAILABLE", str(exc.reason)) from exc

    def profiles(self) -> dict[str, Any]:
        return self._request("GET", "/v1/profiles")

    def snapshot(self) -> dict[str, Any]:
        return self._request("GET", "/v1/snapshot")

    def queue(self) -> list[dict[str, Any]]:
        return self._request("GET", "/v1/queue").get("items", [])

    def submit(
        self,
        profile: str,
        payload: Mapping[str, Any],
        idempotency_key: str,
        priority: int | None = None,
    ) -> dict[str, Any]:
        if not profile.strip():
            raise ValueError("profile is required")
        if not idempotency_key.strip():
            raise ValueError("a stable idempotency_key is required")
        body: dict[str, Any] = {
            "profile": profile,
            "payload": dict(payload),
            "idempotencyKey": idempotency_key,
        }
        if priority is not None:
            body["priority"] = priority
        return self._request("POST", "/v1/jobs", body)

    def get_job(self, job_id: int) -> dict[str, Any]:
        return self._request("GET", f"/v1/jobs/{int(job_id)}")

    def reprioritize(self, job_id: int, priority: int) -> dict[str, Any]:
        return self._request(
            "PATCH", f"/v1/jobs/{int(job_id)}/priority", {"priority": priority}
        )

    def cancel(self, job_id: int) -> dict[str, Any]:
        return self._request("POST", f"/v1/jobs/{int(job_id)}/cancel", {})

    def wait(
        self,
        job_id: int,
        timeout_seconds: float = 3_600,
        poll_interval_seconds: float = 5.0,
    ) -> dict[str, Any]:
        deadline = time.monotonic() + timeout_seconds
        while True:
            job = self.get_job(job_id)
            if job.get("status") in TERMINAL_STATES:
                return job
            if time.monotonic() >= deadline:
                raise TimeoutError(f"GPU scheduler job {job_id} did not finish before timeout")
            time.sleep(max(1.0, poll_interval_seconds))
