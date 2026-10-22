#!/usr/bin/env python3
"""Durable GPU resource scheduler for AID Studio.

The manager is a control plane.  Clients submit allow-listed profiles and never
receive shell access or direct GPU handles.  A node agent is the only component
that claims leases and launches locally configured workers.
"""

from __future__ import annotations

import argparse
import hashlib
import ipaddress
import json
import os
import secrets
import sqlite3
import ssl
import threading
import time
from contextlib import contextmanager
from dataclasses import dataclass
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any
from urllib.parse import parse_qs, urlparse


SCHEMA_VERSION = "aid-gpu-manager/v1"
MAX_BODY_BYTES = 1_048_576
TERMINAL_JOB_STATES = {"SUCCEEDED", "FAILED", "CANCELLED", "LOST"}


DEFAULT_PROFILES: dict[str, dict[str, Any]] = {
    "h3-dual-dense": {
        "gpuCount": 2,
        "exclusive": True,
        "nonPreemptible": True,
        "warmKey": "minimax-h3:ref2va:dense",
        "warmSeconds": 1_200,
        "warmPriorityBoost": 40,
        "switchPenaltySeconds": 600,
        "expectedSeconds": 240,
        "maxRunSeconds": 7_200,
    },
    "h3-single-fast": {
        "gpuCount": 1,
        "exclusive": True,
        "nonPreemptible": True,
        "warmKey": "minimax-h3:fast",
        "warmSeconds": 1_200,
        "warmPriorityBoost": 40,
        "switchPenaltySeconds": 600,
        "expectedSeconds": 180,
        "maxRunSeconds": 3_600,
    },
    "qianchuan-render": {
        "gpuCount": 1,
        "exclusive": True,
        "nonPreemptible": True,
        "warmKey": None,
        "warmSeconds": 0,
        "warmPriorityBoost": 0,
        "switchPenaltySeconds": 30,
        "expectedSeconds": 300,
        "maxRunSeconds": 3_600,
    },
    "douyin-edit": {
        "gpuCount": 1,
        "exclusive": True,
        "nonPreemptible": True,
        "warmKey": None,
        "warmSeconds": 0,
        "warmPriorityBoost": 0,
        "switchPenaltySeconds": 15,
        "expectedSeconds": 180,
        "maxRunSeconds": 3_600,
    },
}


def now_ts() -> float:
    return time.time()


def token_hash(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def parse_key_map(raw: str | None) -> dict[str, str]:
    result: dict[str, str] = {}
    for item in (raw or "").split(","):
        if not item.strip() or "=" not in item:
            continue
        name, key = item.split("=", 1)
        if name.strip() and key.strip():
            result[key.strip()] = name.strip()
    return result


def parse_allowed_networks(raw: str | None) -> list[ipaddress.IPv4Network | ipaddress.IPv6Network]:
    networks = []
    for value in (raw or "").split(","):
        if value.strip():
            networks.append(ipaddress.ip_network(value.strip(), strict=False))
    return networks


def load_profiles(path: str | None) -> dict[str, dict[str, Any]]:
    profiles = json.loads(json.dumps(DEFAULT_PROFILES))
    if path:
        loaded = json.loads(Path(path).read_text(encoding="utf-8"))
        if not isinstance(loaded, dict):
            raise ValueError("profile file must contain a JSON object")
        for name, value in loaded.items():
            if not isinstance(value, dict):
                raise ValueError(f"profile {name!r} must be an object")
            profiles[str(name)] = value
    for name, value in profiles.items():
        count = int(value.get("gpuCount", 0))
        if count < 1:
            raise ValueError(f"profile {name!r} gpuCount must be positive")
        value["gpuCount"] = count
        value["warmSeconds"] = max(0, int(value.get("warmSeconds", 0)))
        value["warmPriorityBoost"] = int(value.get("warmPriorityBoost", 0))
        value["switchPenaltySeconds"] = max(0, int(value.get("switchPenaltySeconds", 0)))
        value["expectedSeconds"] = max(1, int(value.get("expectedSeconds", 300)))
        value["maxRunSeconds"] = max(value["expectedSeconds"], int(value.get("maxRunSeconds", 3_600)))
        value["exclusive"] = bool(value.get("exclusive", True))
        value["nonPreemptible"] = bool(value.get("nonPreemptible", True))
        value["warmKey"] = value.get("warmKey") or None
        value["enabled"] = bool(value.get("enabled", True))
        value["defaultPriority"] = max(0, min(1_000, int(value.get("defaultPriority", 50))))
        value["minClientPriority"] = max(0, min(1_000, int(value.get("minClientPriority", 0))))
        value["maxClientPriority"] = max(0, min(1_000, int(value.get("maxClientPriority", 1_000))))
        if value["minClientPriority"] > value["defaultPriority"] or value["defaultPriority"] > value["maxClientPriority"]:
            raise ValueError(f"profile {name!r} priority bounds must contain defaultPriority")
        indices = value.get("gpuIndices")
        if indices is not None:
            if not isinstance(indices, list) or not indices:
                raise ValueError(f"profile {name!r} gpuIndices must be a non-empty array")
            value["gpuIndices"] = sorted({int(index) for index in indices})
    return profiles


def load_services(path: str | None) -> list[dict[str, Any]]:
    if not path:
        return []
    loaded = json.loads(Path(path).read_text(encoding="utf-8"))
    services = loaded.get("services") if isinstance(loaded, dict) else loaded
    if not isinstance(services, list):
        raise ValueError("service inventory must be an array or an object with a services array")
    result: list[dict[str, Any]] = []
    seen: set[str] = set()
    for value in services:
        if not isinstance(value, dict):
            raise ValueError("every service inventory item must be an object")
        service_id = str(value.get("id") or "").strip()
        profile = str(value.get("profile") or "").strip()
        if not service_id or service_id in seen or not profile:
            raise ValueError("service inventory requires unique id and profile values")
        seen.add(service_id)
        result.append(dict(value, id=service_id, profile=profile))
    return result


@dataclass(frozen=True)
class Principal:
    name: str
    role: str


class SchedulerError(RuntimeError):
    def __init__(self, status: int, code: str, message: str):
        super().__init__(message)
        self.status = status
        self.code = code


class GpuScheduler:
    def __init__(self, db_path: Path, profiles: dict[str, dict[str, Any]] | None = None,
                 services: list[dict[str, Any]] | None = None):
        self.db_path = db_path
        self.db_path.parent.mkdir(parents=True, exist_ok=True)
        self.profiles = profiles or load_profiles(None)
        self.services = services or []
        self._lock = threading.RLock()
        self._init_schema()

    def _connect(self) -> sqlite3.Connection:
        conn = sqlite3.connect(self.db_path, timeout=30, isolation_level=None)
        conn.row_factory = sqlite3.Row
        conn.execute("PRAGMA foreign_keys=ON")
        conn.execute("PRAGMA journal_mode=WAL")
        conn.execute("PRAGMA busy_timeout=30000")
        return conn

    @contextmanager
    def _connection(self):
        conn = self._connect()
        try:
            yield conn
        finally:
            conn.close()

    @contextmanager
    def _transaction(self):
        with self._lock:
            conn = self._connect()
            try:
                conn.execute("BEGIN IMMEDIATE")
                yield conn
                conn.execute("COMMIT")
            except Exception:
                conn.execute("ROLLBACK")
                raise
            finally:
                conn.close()

    def _init_schema(self) -> None:
        with self._connection() as conn:
            conn.executescript(
                """
                CREATE TABLE IF NOT EXISTS nodes (
                    node_id TEXT PRIMARY KEY,
                    hostname TEXT NOT NULL,
                    status TEXT NOT NULL,
                    metadata_json TEXT NOT NULL DEFAULT '{}',
                    last_heartbeat REAL NOT NULL,
                    created_at REAL NOT NULL,
                    updated_at REAL NOT NULL
                );
                CREATE TABLE IF NOT EXISTS gpus (
                    gpu_id TEXT PRIMARY KEY,
                    node_id TEXT NOT NULL REFERENCES nodes(node_id) ON DELETE CASCADE,
                    gpu_index INTEGER NOT NULL,
                    name TEXT NOT NULL,
                    total_vram_mb INTEGER NOT NULL,
                    state TEXT NOT NULL,
                    current_job_id INTEGER,
                    warm_profile TEXT,
                    warm_key TEXT,
                    warm_until REAL,
                    release_requested_at REAL,
                    metrics_json TEXT NOT NULL DEFAULT '{}',
                    last_seen REAL NOT NULL,
                    UNIQUE(node_id, gpu_index)
                );
                CREATE TABLE IF NOT EXISTS jobs (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    idempotency_key TEXT,
                    source TEXT NOT NULL,
                    profile TEXT NOT NULL,
                    status TEXT NOT NULL,
                    priority INTEGER NOT NULL,
                    requested_gpus INTEGER NOT NULL,
                    payload_json TEXT NOT NULL,
                    result_json TEXT,
                    assigned_gpu_ids_json TEXT,
                    worker_node_id TEXT,
                    lease_token_hash TEXT,
                    lease_expires_at REAL,
                    warm_hit INTEGER NOT NULL DEFAULT 0,
                    cancel_requested INTEGER NOT NULL DEFAULT 0,
                    error_message TEXT,
                    created_at REAL NOT NULL,
                    updated_at REAL NOT NULL,
                    started_at REAL,
                    finished_at REAL,
                    UNIQUE(source, idempotency_key)
                );
                CREATE INDEX IF NOT EXISTS idx_jobs_queue ON jobs(status, priority DESC, created_at ASC);
                CREATE INDEX IF NOT EXISTS idx_gpus_node_state ON gpus(node_id, state);
                CREATE TABLE IF NOT EXISTS job_events (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    job_id INTEGER,
                    event_type TEXT NOT NULL,
                    detail_json TEXT NOT NULL DEFAULT '{}',
                    created_at REAL NOT NULL
                );
                CREATE TABLE IF NOT EXISTS security_events (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    fingerprint TEXT NOT NULL UNIQUE,
                    node_id TEXT NOT NULL,
                    gpu_id TEXT NOT NULL,
                    event_type TEXT NOT NULL,
                    severity TEXT NOT NULL,
                    status TEXT NOT NULL,
                    pid INTEGER NOT NULL,
                    process_start_id TEXT NOT NULL,
                    process_name TEXT NOT NULL,
                    username TEXT,
                    used_vram_mb REAL,
                    command_line TEXT,
                    cgroup TEXT,
                    detected_reason TEXT NOT NULL,
                    matched_job_id INTEGER,
                    matched_profile TEXT,
                    first_seen REAL NOT NULL,
                    last_seen REAL NOT NULL,
                    occurrence_count INTEGER NOT NULL DEFAULT 1,
                    acknowledged_at REAL,
                    acknowledged_by TEXT,
                    termination_requested_at REAL,
                    termination_requested_by TEXT,
                    termination_reason TEXT,
                    termination_status TEXT,
                    termination_result TEXT,
                    terminated_at REAL
                );
                CREATE INDEX IF NOT EXISTS idx_security_events_status
                    ON security_events(status, last_seen DESC);
                CREATE TABLE IF NOT EXISTS security_audit (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    event_id INTEGER NOT NULL,
                    action TEXT NOT NULL,
                    actor TEXT NOT NULL,
                    detail_json TEXT NOT NULL DEFAULT '{}',
                    created_at REAL NOT NULL
                );
                CREATE TABLE IF NOT EXISTS profile_policies (
                    profile TEXT PRIMARY KEY,
                    enabled INTEGER NOT NULL,
                    default_priority INTEGER NOT NULL,
                    updated_at REAL NOT NULL,
                    updated_by TEXT NOT NULL
                );
                """
            )
            job_columns = {row[1] for row in conn.execute("PRAGMA table_info(jobs)").fetchall()}
            if "result_json" not in job_columns:
                conn.execute("ALTER TABLE jobs ADD COLUMN result_json TEXT")

    @staticmethod
    def _json(value: Any) -> str:
        return json.dumps(value, ensure_ascii=False, separators=(",", ":"))

    @staticmethod
    def _row(row: sqlite3.Row | None) -> dict[str, Any] | None:
        if row is None:
            return None
        result = dict(row)
        for key in ("payload_json", "result_json", "assigned_gpu_ids_json", "metadata_json", "metrics_json"):
            if key in result:
                value = result.pop(key)
                result[key.removesuffix("_json")] = json.loads(value) if value else None
        for key in ("warm_hit", "cancel_requested"):
            if key in result:
                result[key] = bool(result[key])
        return result

    def _event(self, conn: sqlite3.Connection, job_id: int | None, kind: str, detail: Any = None) -> None:
        conn.execute(
            "INSERT INTO job_events(job_id,event_type,detail_json,created_at) VALUES(?,?,?,?)",
            (job_id, kind, self._json(detail or {}), now_ts()),
        )

    def register_node(self, node_id: str, hostname: str, gpus: list[dict[str, Any]], metadata: dict[str, Any] | None = None) -> dict[str, Any]:
        if not node_id or not hostname or not gpus:
            raise SchedulerError(400, "INVALID_NODE", "nodeId, hostname and at least one GPU are required")
        now = now_ts()
        with self._transaction() as conn:
            conn.execute(
                """INSERT INTO nodes(node_id,hostname,status,metadata_json,last_heartbeat,created_at,updated_at)
                   VALUES(?,?,?,?,?,?,?)
                   ON CONFLICT(node_id) DO UPDATE SET hostname=excluded.hostname,status='ONLINE',
                   metadata_json=excluded.metadata_json,last_heartbeat=excluded.last_heartbeat,updated_at=excluded.updated_at""",
                (node_id, hostname, "ONLINE", self._json(metadata or {}), now, now, now),
            )
            seen: set[str] = set()
            for item in gpus:
                gpu_id = str(item.get("uuid") or "").strip()
                if not gpu_id:
                    raise SchedulerError(400, "INVALID_GPU", "every GPU requires a stable uuid")
                seen.add(gpu_id)
                conn.execute(
                    """INSERT INTO gpus(gpu_id,node_id,gpu_index,name,total_vram_mb,state,last_seen,metrics_json)
                       VALUES(?,?,?,?,?,'FREE',?,?)
                       ON CONFLICT(gpu_id) DO UPDATE SET node_id=excluded.node_id,gpu_index=excluded.gpu_index,
                       name=excluded.name,total_vram_mb=excluded.total_vram_mb,last_seen=excluded.last_seen""",
                    (gpu_id, node_id, int(item.get("index", 0)), str(item.get("name") or "GPU"),
                     int(item.get("totalVramMb", 0)), now, self._json(item.get("metrics") or {})),
                )
            if seen:
                placeholders = ",".join("?" for _ in seen)
                conn.execute(
                    f"UPDATE gpus SET state='OFFLINE',current_job_id=NULL WHERE node_id=? AND gpu_id NOT IN ({placeholders})",
                    (node_id, *sorted(seen)),
                )
        return self.node_snapshot(node_id)

    def heartbeat_node(self, node_id: str, gpus: list[dict[str, Any]]) -> dict[str, Any]:
        now = now_ts()
        with self._transaction() as conn:
            updated = conn.execute(
                "UPDATE nodes SET status='ONLINE',last_heartbeat=?,updated_at=? WHERE node_id=?",
                (now, now, node_id),
            ).rowcount
            if not updated:
                raise SchedulerError(404, "NODE_NOT_FOUND", "node must register before heartbeat")
            for item in gpus:
                gpu_id = str(item.get("uuid") or "")
                conn.execute(
                    "UPDATE gpus SET metrics_json=?,last_seen=? WHERE node_id=? AND gpu_id=?",
                    (self._json(item.get("metrics") or {}), now, node_id, gpu_id),
                )
        return self.node_snapshot(node_id)

    def node_snapshot(self, node_id: str) -> dict[str, Any]:
        with self._connection() as conn:
            node = self._row(conn.execute("SELECT * FROM nodes WHERE node_id=?", (node_id,)).fetchone())
            if node is None:
                raise SchedulerError(404, "NODE_NOT_FOUND", "node not found")
            node["gpus"] = [self._row(row) for row in conn.execute(
                "SELECT * FROM gpus WHERE node_id=? ORDER BY gpu_index", (node_id,)
            ).fetchall()]
            return node

    def get_profile(self, profile_name: str) -> dict[str, Any] | None:
        base = self.profiles.get(profile_name)
        if base is None:
            return None
        result = dict(base)
        with self._connection() as conn:
            policy = conn.execute(
                "SELECT enabled,default_priority FROM profile_policies WHERE profile=?", (profile_name,)
            ).fetchone()
        if policy is not None:
            result["enabled"] = bool(policy["enabled"])
            result["defaultPriority"] = int(policy["default_priority"])
            if int(base.get("maxClientPriority", 1_000)) == int(base.get("defaultPriority", 50)):
                result["maxClientPriority"] = result["defaultPriority"]
        return result

    def list_profiles(self, include_disabled: bool = False) -> dict[str, dict[str, Any]]:
        result: dict[str, dict[str, Any]] = {}
        for name in self.profiles:
            profile = self.get_profile(name)
            if profile is not None and (include_disabled or profile.get("enabled", True)):
                result[name] = profile
        return result

    def list_service_catalog(self) -> list[dict[str, Any]]:
        result: list[dict[str, Any]] = []
        for service in self.services:
            item = dict(service)
            profile = self.get_profile(item["profile"])
            item["configured"] = profile is not None
            item["adapterReady"] = bool(item.get("adapterReady", False))
            item["enabled"] = bool(profile.get("enabled", True)) if profile else False
            item["defaultPriority"] = int(profile.get("defaultPriority", 50)) if profile else None
            item["gpuCount"] = int(profile.get("gpuCount", 0)) if profile else None
            item["gpuIndices"] = profile.get("gpuIndices") if profile else None
            item["warmSeconds"] = int(profile.get("warmSeconds", 0)) if profile else None
            result.append(item)
        return result

    def update_service_policy(self, service_id: str, enabled: bool | None,
                              default_priority: int | None, actor: str) -> dict[str, Any]:
        service = next((item for item in self.services if item["id"] == service_id), None)
        if service is None:
            raise SchedulerError(404, "GPU_SERVICE_NOT_FOUND", "GPU service was not found in the inventory")
        profile_name = service["profile"]
        if enabled is True and not bool(service.get("adapterReady", False)):
            raise SchedulerError(409, "SERVICE_ADAPTER_NOT_READY", "fixed node adapter has not passed validation")
        current = self.get_profile(profile_name)
        if current is None:
            raise SchedulerError(409, "PROFILE_NOT_CONFIGURED", "service profile is not configured")
        next_enabled = bool(current.get("enabled", True)) if enabled is None else bool(enabled)
        next_priority = int(current.get("defaultPriority", 50)) if default_priority is None else int(default_priority)
        if not 0 <= next_priority <= 1_000:
            raise SchedulerError(400, "INVALID_PRIORITY", "priority must be between 0 and 1000")
        with self._transaction() as conn:
            conn.execute(
                """INSERT INTO profile_policies(profile,enabled,default_priority,updated_at,updated_by)
                   VALUES(?,?,?,?,?) ON CONFLICT(profile) DO UPDATE SET enabled=excluded.enabled,
                   default_priority=excluded.default_priority,updated_at=excluded.updated_at,
                   updated_by=excluded.updated_by""",
                (profile_name, 1 if next_enabled else 0, next_priority, now_ts(), actor),
            )
        return next(item for item in self.list_service_catalog() if item["id"] == service_id)

    def submit_job(self, source: str, body: dict[str, Any]) -> dict[str, Any]:
        profile_name = str(body.get("profile") or "").strip()
        profile = self.get_profile(profile_name)
        if profile is None:
            raise SchedulerError(400, "PROFILE_NOT_ALLOWED", "unknown or disabled GPU profile")
        if not profile.get("enabled", True):
            raise SchedulerError(409, "PROFILE_DISABLED", "GPU profile is disabled by an administrator")
        priority = int(body.get("priority", profile.get("defaultPriority", 50)))
        if not 0 <= priority <= 1_000:
            raise SchedulerError(400, "INVALID_PRIORITY", "priority must be between 0 and 1000")
        if not int(profile.get("minClientPriority", 0)) <= priority <= int(profile.get("maxClientPriority", 1_000)):
            raise SchedulerError(403, "PRIORITY_NOT_ALLOWED", "priority is outside the administrator-approved range")
        payload = body.get("payload") or {}
        if not isinstance(payload, dict):
            raise SchedulerError(400, "INVALID_PAYLOAD", "payload must be a JSON object")
        idem = str(body.get("idempotencyKey") or "").strip() or None
        now = now_ts()
        with self._transaction() as conn:
            if idem:
                existing = conn.execute(
                    "SELECT * FROM jobs WHERE source=? AND idempotency_key=?", (source, idem)
                ).fetchone()
                if existing:
                    return self._decorate_job(conn, existing)
            cur = conn.execute(
                """INSERT INTO jobs(idempotency_key,source,profile,status,priority,requested_gpus,payload_json,
                   created_at,updated_at) VALUES(?,?,?,'QUEUED',?,?,?,?,?)""",
                (idem, source, profile_name, priority, int(profile["gpuCount"]), self._json(payload), now, now),
            )
            job_id = int(cur.lastrowid)
            self._event(conn, job_id, "QUEUED", {"source": source, "profile": profile_name, "priority": priority})
            row = conn.execute("SELECT * FROM jobs WHERE id=?", (job_id,)).fetchone()
            return self._decorate_job(conn, row)

    def _effective_priority(self, row: sqlite3.Row, warm_keys: set[str], now: float) -> float:
        profile = self.get_profile(row["profile"]) or {}
        age_boost = max(0.0, now - float(row["created_at"])) / 600.0
        warm_boost = int(profile.get("warmPriorityBoost", 0)) if profile.get("warmKey") in warm_keys else 0
        return float(row["priority"]) + age_boost + warm_boost

    def _queue_rows(self, conn: sqlite3.Connection) -> list[sqlite3.Row]:
        now = now_ts()
        warm_keys = {row[0] for row in conn.execute(
            "SELECT DISTINCT warm_key FROM gpus WHERE state='WARM' AND warm_key IS NOT NULL"
        ).fetchall()}
        rows = conn.execute("SELECT * FROM jobs WHERE status='QUEUED'").fetchall()
        return sorted(rows, key=lambda row: (-self._effective_priority(row, warm_keys, now), row["created_at"], row["id"]))

    def _decorate_job(self, conn: sqlite3.Connection, row: sqlite3.Row) -> dict[str, Any]:
        result = self._row(row) or {}
        if row["status"] == "QUEUED":
            queue = self._queue_rows(conn)
            position = next((index + 1 for index, item in enumerate(queue) if item["id"] == row["id"]), None)
            result["queuePosition"] = position
            ahead = queue[: max(0, (position or 1) - 1)]
            result["estimatedStartSeconds"] = sum(
                int((self.get_profile(item["profile"]) or {}).get("expectedSeconds", 300)) for item in ahead
            )
            profile = self.get_profile(row["profile"]) or {}
            warm = conn.execute(
                "SELECT COUNT(*) FROM gpus WHERE state='WARM' AND warm_key=?",
                (profile.get("warmKey"),),
            ).fetchone()[0] if profile.get("warmKey") else 0
            warm_hit = warm >= int(profile.get("gpuCount", 1))
            result["estimatedColdStartSeconds"] = 0 if warm_hit else int(profile.get("switchPenaltySeconds", 0))
        return result

    def get_job(self, job_id: int, source: str | None = None, admin: bool = False) -> dict[str, Any]:
        with self._connection() as conn:
            row = conn.execute("SELECT * FROM jobs WHERE id=?", (job_id,)).fetchone()
            if row is None:
                raise SchedulerError(404, "JOB_NOT_FOUND", "job not found")
            if not admin and source is not None and row["source"] != source:
                raise SchedulerError(403, "JOB_FORBIDDEN", "a client may only inspect its own job")
            return self._decorate_job(conn, row)

    def list_queue(self, source: str | None = None) -> list[dict[str, Any]]:
        with self._connection() as conn:
            return [self._decorate_job(conn, row) for row in self._queue_rows(conn)
                    if source is None or row["source"] == source]

    def update_priority(self, job_id: int, source: str, priority: int, admin: bool = False) -> dict[str, Any]:
        if not 0 <= priority <= 1_000:
            raise SchedulerError(400, "INVALID_PRIORITY", "priority must be between 0 and 1000")
        with self._transaction() as conn:
            row = conn.execute("SELECT * FROM jobs WHERE id=?", (job_id,)).fetchone()
            if row is None:
                raise SchedulerError(404, "JOB_NOT_FOUND", "job not found")
            if not admin and row["source"] != source:
                raise SchedulerError(403, "JOB_FORBIDDEN", "a client may only reprioritize its own job")
            if not admin:
                profile = self.get_profile(row["profile"]) or {}
                if not int(profile.get("minClientPriority", 0)) <= priority <= int(profile.get("maxClientPriority", 1_000)):
                    raise SchedulerError(403, "PRIORITY_NOT_ALLOWED", "priority is outside the administrator-approved range")
            if row["status"] != "QUEUED":
                raise SchedulerError(409, "JOB_ALREADY_STARTED", "only queued jobs can change priority")
            conn.execute("UPDATE jobs SET priority=?,updated_at=? WHERE id=?", (priority, now_ts(), job_id))
            self._event(conn, job_id, "PRIORITY_CHANGED", {"priority": priority, "actor": source})
            return self._decorate_job(conn, conn.execute("SELECT * FROM jobs WHERE id=?", (job_id,)).fetchone())

    def cancel_job(self, job_id: int, source: str, admin: bool = False) -> dict[str, Any]:
        with self._transaction() as conn:
            row = conn.execute("SELECT * FROM jobs WHERE id=?", (job_id,)).fetchone()
            if row is None:
                raise SchedulerError(404, "JOB_NOT_FOUND", "job not found")
            if not admin and row["source"] != source:
                raise SchedulerError(403, "JOB_FORBIDDEN", "a client may only cancel its own job")
            if row["status"] == "QUEUED":
                now = now_ts()
                conn.execute("UPDATE jobs SET status='CANCELLED',finished_at=?,updated_at=? WHERE id=?", (now, now, job_id))
                self._event(conn, job_id, "CANCELLED", {"actor": source})
            elif row["status"] == "RUNNING":
                conn.execute("UPDATE jobs SET cancel_requested=1,updated_at=? WHERE id=?", (now_ts(), job_id))
                self._event(conn, job_id, "CANCEL_REQUESTED", {"actor": source, "deferred": True})
            return self._decorate_job(conn, conn.execute("SELECT * FROM jobs WHERE id=?", (job_id,)).fetchone())

    def _quarantine_expired_leases(self, conn: sqlite3.Connection, now: float) -> None:
        rows = conn.execute(
            "SELECT id FROM jobs WHERE status='RUNNING' AND lease_expires_at IS NOT NULL AND lease_expires_at<?",
            (now,),
        ).fetchall()
        for row in rows:
            job_id = int(row["id"])
            conn.execute(
                "UPDATE jobs SET status='LOST',error_message='lease heartbeat expired',finished_at=?,updated_at=? WHERE id=?",
                (now, now, job_id),
            )
            conn.execute(
                "UPDATE gpus SET state='QUARANTINED',current_job_id=NULL,warm_profile=NULL,warm_key=NULL,warm_until=NULL WHERE current_job_id=?",
                (job_id,),
            )
            self._event(conn, job_id, "LEASE_EXPIRED", {"gpuState": "QUARANTINED"})

    def _release_action(self, conn: sqlite3.Connection, node_id: str, now: float) -> dict[str, Any] | None:
        rows = conn.execute(
            """SELECT * FROM gpus WHERE node_id=? AND state IN ('WARM','RELEASING')
               ORDER BY gpu_index""",
            (node_id,),
        ).fetchall()
        expired = [row for row in rows if row["state"] == "RELEASING" or (row["warm_until"] or 0) <= now]
        if not expired:
            return None
        profile = expired[0]["warm_profile"]
        group = [row for row in rows if row["warm_profile"] == profile]
        gpu_ids = [row["gpu_id"] for row in group]
        conn.executemany(
            "UPDATE gpus SET state='RELEASING',release_requested_at=? WHERE gpu_id=?",
            [(now, gpu_id) for gpu_id in gpu_ids],
        )
        return {"action": "RELEASE_WARM", "profile": profile, "gpuIds": gpu_ids}

    def claim(self, node_id: str, lease_seconds: int = 45) -> dict[str, Any]:
        now = now_ts()
        lease_seconds = max(15, min(int(lease_seconds), 300))
        with self._transaction() as conn:
            node = conn.execute("SELECT * FROM nodes WHERE node_id=?", (node_id,)).fetchone()
            if node is None:
                raise SchedulerError(404, "NODE_NOT_FOUND", "node must register before claiming")
            self._quarantine_expired_leases(conn, now)
            release = self._release_action(conn, node_id, now)
            if release:
                return release
            gpus = conn.execute(
                "SELECT * FROM gpus WHERE node_id=? AND state IN ('FREE','WARM') ORDER BY gpu_index",
                (node_id,),
            ).fetchall()
            blocked_gpu_ids = {
                row["gpu_id"]
                for row in conn.execute(
                    """SELECT DISTINCT gpu_id FROM security_events
                       WHERE node_id=? AND status NOT IN ('RESOLVED','TERMINATED')""",
                    (node_id,),
                ).fetchall()
            }
            gpus = [row for row in gpus if row["gpu_id"] not in blocked_gpu_ids]
            for job in self._queue_rows(conn):
                profile = self.get_profile(job["profile"])
                if not profile:
                    continue
                count = int(profile["gpuCount"])
                warm_key = profile.get("warmKey")
                allowed_indices = profile.get("gpuIndices")
                eligible = [row for row in gpus if allowed_indices is None or row["gpu_index"] in allowed_indices]
                warm = [row for row in eligible if row["state"] == "WARM" and warm_key and row["warm_key"] == warm_key]
                free = [row for row in eligible if row["state"] == "FREE"]
                warm_hit = len(warm) >= count
                if warm_hit:
                    selected = warm[:count]
                elif len(free) >= count and not any(row["state"] == "WARM" for row in eligible):
                    selected = free[:count]
                else:
                    continue
                raw_token = secrets.token_urlsafe(32)
                gpu_ids = [row["gpu_id"] for row in selected]
                expires = now + lease_seconds
                changed = conn.execute(
                    """UPDATE jobs SET status='RUNNING',assigned_gpu_ids_json=?,worker_node_id=?,lease_token_hash=?,
                       lease_expires_at=?,warm_hit=?,started_at=?,updated_at=? WHERE id=? AND status='QUEUED'""",
                    (self._json(gpu_ids), node_id, token_hash(raw_token), expires, 1 if warm_hit else 0,
                     now, now, job["id"]),
                ).rowcount
                if not changed:
                    continue
                conn.executemany(
                    """UPDATE gpus SET state='RUNNING',current_job_id=?,warm_profile=NULL,warm_key=NULL,
                       warm_until=NULL,release_requested_at=NULL WHERE gpu_id=?""",
                    [(job["id"], gpu_id) for gpu_id in gpu_ids],
                )
                self._event(conn, int(job["id"]), "LEASE_GRANTED", {"nodeId": node_id, "gpuIds": gpu_ids, "warmHit": warm_hit})
                decorated = self._decorate_job(conn, conn.execute("SELECT * FROM jobs WHERE id=?", (job["id"],)).fetchone())
                return {
                    "action": "RUN_JOB",
                    "job": decorated,
                    "leaseToken": raw_token,
                    "leaseExpiresAt": expires,
                    "profileConfig": profile,
                }
            return {"action": "WAIT", "retryAfterSeconds": 2}

    def heartbeat_job(self, job_id: int, node_id: str, lease_token: str, lease_seconds: int = 45) -> dict[str, Any]:
        with self._transaction() as conn:
            row = conn.execute("SELECT * FROM jobs WHERE id=?", (job_id,)).fetchone()
            self._verify_lease(row, node_id, lease_token)
            expires = now_ts() + max(15, min(int(lease_seconds), 300))
            conn.execute("UPDATE jobs SET lease_expires_at=?,updated_at=? WHERE id=?", (expires, now_ts(), job_id))
            return {"jobId": job_id, "leaseExpiresAt": expires, "cancelRequested": bool(row["cancel_requested"])}

    @staticmethod
    def _verify_lease(row: sqlite3.Row | None, node_id: str, lease_token: str) -> None:
        if row is None:
            raise SchedulerError(404, "JOB_NOT_FOUND", "job not found")
        if row["status"] != "RUNNING" or row["worker_node_id"] != node_id:
            raise SchedulerError(409, "LEASE_NOT_ACTIVE", "job is not running on this node")
        if not secrets.compare_digest(str(row["lease_token_hash"] or ""), token_hash(lease_token)):
            raise SchedulerError(403, "LEASE_FORBIDDEN", "invalid lease token")

    def finish_job(self, job_id: int, node_id: str, lease_token: str, success: bool,
                   error: str | None = None, result: dict[str, Any] | None = None) -> dict[str, Any]:
        if result is not None and not isinstance(result, dict):
            raise SchedulerError(400, "INVALID_RESULT", "result must be a JSON object")
        if result is not None and len(self._json(result).encode("utf-8")) > MAX_BODY_BYTES:
            raise SchedulerError(413, "RESULT_TOO_LARGE", "result exceeds the maximum JSON size")
        now = now_ts()
        with self._transaction() as conn:
            row = conn.execute("SELECT * FROM jobs WHERE id=?", (job_id,)).fetchone()
            self._verify_lease(row, node_id, lease_token)
            profile = self.get_profile(row["profile"]) or {}
            gpu_ids = json.loads(row["assigned_gpu_ids_json"] or "[]")
            final_state = "SUCCEEDED" if success else "FAILED"
            conn.execute(
                """UPDATE jobs SET status=?,error_message=?,result_json=?,lease_token_hash=NULL,lease_expires_at=NULL,
                   finished_at=?,updated_at=? WHERE id=?""",
                (final_state, error, self._json(result) if result is not None else None, now, now, job_id),
            )
            warm_seconds = int(profile.get("warmSeconds", 0)) if success else 0
            if warm_seconds > 0:
                conn.executemany(
                    """UPDATE gpus SET state='WARM',current_job_id=NULL,warm_profile=?,warm_key=?,warm_until=?,
                       release_requested_at=NULL WHERE gpu_id=?""",
                    [(row["profile"], profile.get("warmKey"), now + warm_seconds, gpu_id) for gpu_id in gpu_ids],
                )
            else:
                conn.executemany(
                    """UPDATE gpus SET state='FREE',current_job_id=NULL,warm_profile=NULL,warm_key=NULL,
                       warm_until=NULL,release_requested_at=NULL WHERE gpu_id=?""",
                    [(gpu_id,) for gpu_id in gpu_ids],
                )
            self._event(conn, job_id, final_state, {"warmSeconds": warm_seconds, "error": error})
            return self._decorate_job(conn, conn.execute("SELECT * FROM jobs WHERE id=?", (job_id,)).fetchone())

    def acknowledge_release(self, node_id: str, profile: str, gpu_ids: list[str]) -> dict[str, Any]:
        with self._transaction() as conn:
            rows = conn.execute(
                "SELECT * FROM gpus WHERE node_id=? AND warm_profile=? AND state='RELEASING'",
                (node_id, profile),
            ).fetchall()
            expected = {row["gpu_id"] for row in rows}
            requested = {str(value) for value in gpu_ids}
            if not expected or expected != requested:
                raise SchedulerError(409, "RELEASE_MISMATCH", "release acknowledgement does not match pending GPUs")
            conn.executemany(
                """UPDATE gpus SET state='FREE',current_job_id=NULL,warm_profile=NULL,warm_key=NULL,
                   warm_until=NULL,release_requested_at=NULL WHERE gpu_id=?""",
                [(gpu_id,) for gpu_id in sorted(expected)],
            )
            return {"released": sorted(expected), "profile": profile}

    def request_warm_release(self, node_id: str, profile: str) -> dict[str, Any]:
        with self._transaction() as conn:
            now = now_ts()
            changed = conn.execute(
                """UPDATE gpus SET warm_until=?,state='RELEASING',release_requested_at=?
                   WHERE node_id=? AND warm_profile=? AND state='WARM'""",
                (now, now, node_id, profile),
            ).rowcount
            return {"nodeId": node_id, "profile": profile, "releaseRequested": changed}

    def reconcile_gpus(self, node_id: str, gpu_ids: list[str]) -> dict[str, Any]:
        requested = {str(value) for value in gpu_ids}
        if not requested:
            raise SchedulerError(400, "INVALID_GPU", "at least one gpuId is required")
        with self._transaction() as conn:
            placeholders = ",".join("?" for _ in requested)
            rows = conn.execute(
                f"SELECT * FROM gpus WHERE node_id=? AND gpu_id IN ({placeholders})",
                (node_id, *sorted(requested)),
            ).fetchall()
            if {row["gpu_id"] for row in rows} != requested:
                raise SchedulerError(404, "GPU_NOT_FOUND", "one or more GPUs were not found on the node")
            invalid = [row["gpu_id"] for row in rows if row["state"] not in {"QUARANTINED", "OFFLINE"}]
            if invalid:
                raise SchedulerError(409, "GPU_NOT_QUARANTINED", f"GPUs are not eligible for reconciliation: {invalid}")
            conn.executemany(
                """UPDATE gpus SET state='FREE',current_job_id=NULL,warm_profile=NULL,warm_key=NULL,
                   warm_until=NULL,release_requested_at=NULL WHERE gpu_id=?""",
                [(gpu_id,) for gpu_id in sorted(requested)],
            )
            return {"nodeId": node_id, "reconciled": sorted(requested)}

    @staticmethod
    def _security_row(row: sqlite3.Row | None) -> dict[str, Any] | None:
        return dict(row) if row is not None else None

    @staticmethod
    def _security_fingerprint(node_id: str, gpu_id: str, pid: int, process_start_id: str) -> str:
        raw = f"{node_id}\0{gpu_id}\0{pid}\0{process_start_id}".encode("utf-8")
        return hashlib.sha256(raw).hexdigest()

    def _process_authorized(self, conn: sqlite3.Connection, node_id: str,
                            gpu_id: str, process: dict[str, Any]) -> tuple[bool, str]:
        gpu = conn.execute(
            "SELECT * FROM gpus WHERE node_id=? AND gpu_id=?", (node_id, gpu_id)
        ).fetchone()
        if gpu is None:
            return False, "GPU 未在调度器登记"
        matched_job = process.get("matchedJobId")
        matched_profile = str(process.get("matchedProfile") or "")
        if process.get("matchedInfrastructure"):
            limit = max(0.0, float(process.get("infrastructureMaxIdleVramMb") or 0))
            used = max(0.0, float(process.get("usedVramMb") or 0))
            if limit > 0 and used <= limit:
                return True, f"匹配节点空闲基础服务白名单（{used:.0f}/{limit:.0f} MiB）"
        if gpu["state"] == "RUNNING" and gpu["current_job_id"] is not None:
            job = conn.execute("SELECT profile FROM jobs WHERE id=?", (gpu["current_job_id"],)).fetchone()
            if matched_job is not None and int(matched_job) == int(gpu["current_job_id"]):
                return True, "匹配当前 GPU 租约与作业进程组"
            if job is not None and matched_profile and matched_profile == job["profile"]:
                return True, "匹配当前 GPU 租约的节点白名单"
            return False, f"GPU 已租给作业 {gpu['current_job_id']}，进程不属于该租约"
        if gpu["state"] in {"WARM", "RELEASING"}:
            if matched_profile and matched_profile == str(gpu["warm_profile"] or ""):
                return True, "匹配热驻留模型的节点白名单"
            return False, "GPU 处于热驻留状态，但进程不属于热驻留模型"
        return False, f"GPU 当前状态为 {gpu['state']}，没有有效使用租约"

    def report_gpu_processes(self, node_id: str, processes: list[dict[str, Any]]) -> dict[str, Any]:
        if not isinstance(processes, list):
            raise SchedulerError(400, "INVALID_PROCESS_REPORT", "processes must be an array")
        now = now_ts()
        observed_unauthorized: set[str] = set()
        with self._transaction() as conn:
            if conn.execute("SELECT 1 FROM nodes WHERE node_id=?", (node_id,)).fetchone() is None:
                raise SchedulerError(404, "NODE_NOT_FOUND", "node must register before process reporting")
            for process in processes:
                gpu_id = str(process.get("gpuId") or "").strip()
                pid = int(process.get("pid") or 0)
                start_id = str(process.get("processStartId") or "").strip()
                if not gpu_id or pid <= 0 or not start_id:
                    raise SchedulerError(400, "INVALID_PROCESS_REPORT", "gpuId, pid and processStartId are required")
                authorized, reason = self._process_authorized(conn, node_id, gpu_id, process)
                if authorized:
                    continue
                fingerprint = self._security_fingerprint(node_id, gpu_id, pid, start_id)
                observed_unauthorized.add(fingerprint)
                existing = conn.execute(
                    "SELECT id,status FROM security_events WHERE fingerprint=?", (fingerprint,)
                ).fetchone()
                values = (
                    str(process.get("processName") or "unknown")[:512],
                    str(process.get("username") or "")[:256] or None,
                    float(process.get("usedVramMb") or 0),
                    str(process.get("commandLine") or "")[:4096] or None,
                    str(process.get("cgroup") or "")[:2048] or None,
                    reason[:1024],
                    int(process["matchedJobId"]) if process.get("matchedJobId") is not None else None,
                    str(process.get("matchedProfile") or "")[:256] or None,
                )
                if existing is None:
                    conn.execute(
                        """INSERT INTO security_events(
                           fingerprint,node_id,gpu_id,event_type,severity,status,pid,process_start_id,
                           process_name,username,used_vram_mb,command_line,cgroup,detected_reason,
                           matched_job_id,matched_profile,first_seen,last_seen)
                           VALUES(?,?,?,'UNAUTHORIZED_GPU_PROCESS','CRITICAL','OPEN',?,?,?,?,?,?,?,?,?,?,?,?)""",
                        (fingerprint, node_id, gpu_id, pid, start_id, *values, now, now),
                    )
                else:
                    status = existing["status"]
                    if status in {"RESOLVED", "TERMINATED", "TERMINATION_FAILED"}:
                        status = "OPEN"
                    conn.execute(
                        """UPDATE security_events SET status=?,process_name=?,username=?,used_vram_mb=?,
                           command_line=?,cgroup=?,detected_reason=?,matched_job_id=?,matched_profile=?,
                           last_seen=?,occurrence_count=occurrence_count+1 WHERE fingerprint=?""",
                        (status, *values, now, fingerprint),
                    )
            open_rows = conn.execute(
                "SELECT fingerprint FROM security_events WHERE node_id=? AND status IN ('OPEN','ACKNOWLEDGED')",
                (node_id,),
            ).fetchall()
            for row in open_rows:
                if row["fingerprint"] not in observed_unauthorized:
                    conn.execute(
                        "UPDATE security_events SET status='RESOLVED',last_seen=? WHERE fingerprint=?",
                        (now, row["fingerprint"]),
                    )
            commands = conn.execute(
                """SELECT * FROM security_events WHERE node_id=? AND status='TERMINATION_REQUESTED'
                   ORDER BY termination_requested_at""",
                (node_id,),
            ).fetchall()
            return {
                "unauthorizedCount": len(observed_unauthorized),
                "terminationRequests": [{
                    "eventId": row["id"],
                    "pid": row["pid"],
                    "processStartId": row["process_start_id"],
                    "reason": row["termination_reason"],
                } for row in commands],
            }

    def list_security_events(self, status: str | None = None, limit: int = 100) -> list[dict[str, Any]]:
        limit = max(1, min(int(limit), 500))
        allowed = {"OPEN", "ACKNOWLEDGED", "TERMINATION_REQUESTED", "TERMINATED", "TERMINATION_FAILED", "RESOLVED"}
        if status and status not in allowed:
            raise SchedulerError(400, "INVALID_STATUS", "unsupported security event status")
        with self._connection() as conn:
            if status:
                rows = conn.execute(
                    "SELECT * FROM security_events WHERE status=? ORDER BY last_seen DESC LIMIT ?", (status, limit)
                ).fetchall()
            else:
                rows = conn.execute(
                    "SELECT * FROM security_events ORDER BY last_seen DESC LIMIT ?", (limit,)
                ).fetchall()
            return [dict(row) for row in rows]

    def acknowledge_security_event(self, event_id: int, actor: str) -> dict[str, Any]:
        with self._transaction() as conn:
            row = conn.execute("SELECT * FROM security_events WHERE id=?", (event_id,)).fetchone()
            if row is None:
                raise SchedulerError(404, "SECURITY_EVENT_NOT_FOUND", "security event not found")
            if row["status"] == "OPEN":
                now = now_ts()
                conn.execute(
                    "UPDATE security_events SET status='ACKNOWLEDGED',acknowledged_at=?,acknowledged_by=? WHERE id=?",
                    (now, actor, event_id),
                )
                conn.execute(
                    "INSERT INTO security_audit(event_id,action,actor,detail_json,created_at) VALUES(?,?,?,?,?)",
                    (event_id, "ACKNOWLEDGED", actor, "{}", now),
                )
            return dict(conn.execute("SELECT * FROM security_events WHERE id=?", (event_id,)).fetchone())

    def request_process_termination(self, event_id: int, actor: str, reason: str) -> dict[str, Any]:
        reason = reason.strip()
        if len(reason) < 3:
            raise SchedulerError(400, "TERMINATION_REASON_REQUIRED", "a termination reason of at least 3 characters is required")
        with self._transaction() as conn:
            row = conn.execute("SELECT * FROM security_events WHERE id=?", (event_id,)).fetchone()
            if row is None:
                raise SchedulerError(404, "SECURITY_EVENT_NOT_FOUND", "security event not found")
            if row["status"] not in {"OPEN", "ACKNOWLEDGED", "TERMINATION_FAILED"}:
                raise SchedulerError(409, "EVENT_NOT_TERMINATABLE", "security event is not eligible for termination")
            now = now_ts()
            conn.execute(
                """UPDATE security_events SET status='TERMINATION_REQUESTED',termination_requested_at=?,
                   termination_requested_by=?,termination_reason=?,termination_status='PENDING',termination_result=NULL
                   WHERE id=?""",
                (now, actor, reason[:1024], event_id),
            )
            conn.execute(
                "INSERT INTO security_audit(event_id,action,actor,detail_json,created_at) VALUES(?,?,?,?,?)",
                (event_id, "TERMINATION_REQUESTED", actor, self._json({"reason": reason[:1024]}), now),
            )
            return dict(conn.execute("SELECT * FROM security_events WHERE id=?", (event_id,)).fetchone())

    def complete_process_termination(self, node_id: str, event_id: int, success: bool,
                                     result: str) -> dict[str, Any]:
        with self._transaction() as conn:
            row = conn.execute("SELECT * FROM security_events WHERE id=? AND node_id=?", (event_id, node_id)).fetchone()
            if row is None:
                raise SchedulerError(404, "SECURITY_EVENT_NOT_FOUND", "security event not found on this node")
            if row["status"] != "TERMINATION_REQUESTED":
                raise SchedulerError(409, "TERMINATION_NOT_REQUESTED", "termination is not pending")
            now = now_ts()
            status = "TERMINATED" if success else "TERMINATION_FAILED"
            conn.execute(
                """UPDATE security_events SET status=?,termination_status=?,termination_result=?,terminated_at=?
                   WHERE id=?""",
                (status, "SUCCEEDED" if success else "FAILED", result[:2048], now, event_id),
            )
            conn.execute(
                "INSERT INTO security_audit(event_id,action,actor,detail_json,created_at) VALUES(?,?,?,?,?)",
                (event_id, status, f"agent:{node_id}", self._json({"result": result[:2048]}), now),
            )
            return dict(conn.execute("SELECT * FROM security_events WHERE id=?", (event_id,)).fetchone())

    @staticmethod
    def _redact_job(job: dict[str, Any]) -> dict[str, Any]:
        result = dict(job)
        result.pop("payload", None)
        result.pop("lease_token_hash", None)
        return result

    def snapshot(self, redact_payloads: bool = False) -> dict[str, Any]:
        with self._connection() as conn:
            nodes: list[dict[str, Any]] = []
            for row in conn.execute("SELECT * FROM nodes ORDER BY node_id").fetchall():
                item = self._row(row) or {}
                item["gpus"] = [self._row(gpu) for gpu in conn.execute(
                    "SELECT * FROM gpus WHERE node_id=? ORDER BY gpu_index", (row["node_id"],)
                ).fetchall()]
                nodes.append(item)
            running = [self._decorate_job(conn, row) for row in conn.execute(
                "SELECT * FROM jobs WHERE status='RUNNING' ORDER BY started_at"
            ).fetchall()]
            queued = [self._decorate_job(conn, row) for row in self._queue_rows(conn)]
            if redact_payloads:
                running = [self._redact_job(item) for item in running]
                queued = [self._redact_job(item) for item in queued]
            return {
                "schemaVersion": SCHEMA_VERSION,
                "generatedAt": now_ts(),
                "nodes": nodes,
                "running": running,
                "queued": queued,
                "profiles": self.list_profiles(include_disabled=not redact_payloads),
                "services": self.list_service_catalog() if not redact_payloads else [],
            }


class GpuManagerApplication:
    def __init__(self, scheduler: GpuScheduler, client_keys: dict[str, str], agent_token: str,
                 admin_token: str, allowed_networks: list[Any] | None = None):
        self.scheduler = scheduler
        self.client_keys = client_keys
        self.agent_token = agent_token
        self.admin_token = admin_token
        self.allowed_networks = allowed_networks or []

    def authorize_address(self, address: str) -> None:
        if not self.allowed_networks:
            return
        try:
            value = ipaddress.ip_address(address)
        except ValueError as exc:
            raise SchedulerError(403, "SOURCE_NOT_ALLOWED", "client source address is invalid") from exc
        if not any(value in network for network in self.allowed_networks):
            raise SchedulerError(403, "SOURCE_NOT_ALLOWED", "client source network is not allowed")

    def authenticate(self, headers: Any, role: str = "client") -> Principal:
        auth = str(headers.get("Authorization") or "")
        token = auth[7:].strip() if auth.lower().startswith("bearer ") else ""
        if role == "agent" and self.agent_token and secrets.compare_digest(token, self.agent_token):
            return Principal("gpu-agent", "agent")
        if role == "admin" and self.admin_token and secrets.compare_digest(token, self.admin_token):
            return Principal("gpu-admin", "admin")
        if role == "client" and token in self.client_keys:
            return Principal(self.client_keys[token], "client")
        raise SchedulerError(401, "UNAUTHORIZED", "valid bearer token required")

    def authenticate_client_or_admin(self, headers: Any) -> Principal:
        auth = str(headers.get("Authorization") or "")
        token = auth[7:].strip() if auth.lower().startswith("bearer ") else ""
        if self.admin_token and secrets.compare_digest(token, self.admin_token):
            return Principal("gpu-admin", "admin")
        return self.authenticate(headers, "client")


def make_handler(app: GpuManagerApplication):
    class Handler(BaseHTTPRequestHandler):
        server_version = "AIDGpuManager/1.0"

        def log_message(self, fmt: str, *args: Any) -> None:
            print(f"gpu-manager {self.address_string()} {fmt % args}", flush=True)

        def _json_body(self) -> dict[str, Any]:
            length = int(self.headers.get("Content-Length") or 0)
            if length < 0 or length > MAX_BODY_BYTES:
                raise SchedulerError(413, "BODY_TOO_LARGE", "request body too large")
            raw = self.rfile.read(length) if length else b"{}"
            try:
                body = json.loads(raw)
            except json.JSONDecodeError as exc:
                raise SchedulerError(400, "INVALID_JSON", str(exc)) from exc
            if not isinstance(body, dict):
                raise SchedulerError(400, "INVALID_JSON", "request body must be an object")
            return body

        def _send(self, status: int, payload: Any) -> None:
            data = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(data)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(data)

        def _dispatch(self) -> tuple[int, Any]:
            app.authorize_address(self.client_address[0])
            path = urlparse(self.path).path.rstrip("/") or "/"
            parts = [part for part in path.split("/") if part]
            if self.command == "GET" and path == "/health":
                return 200, {"status": "ok", "schemaVersion": SCHEMA_VERSION}
            if self.command == "GET" and path == "/v1/profiles":
                app.authenticate(self.headers)
                return 200, app.scheduler.list_profiles()
            if self.command == "POST" and path == "/v1/jobs":
                principal = app.authenticate(self.headers)
                return 202, app.scheduler.submit_job(principal.name, self._json_body())
            if self.command == "GET" and path == "/v1/queue":
                principal = app.authenticate_client_or_admin(self.headers)
                return 200, {"items": app.scheduler.list_queue(None if principal.role == "admin" else principal.name)}
            if len(parts) == 3 and parts[:2] == ["v1", "jobs"] and self.command == "GET":
                principal = app.authenticate_client_or_admin(self.headers)
                return 200, app.scheduler.get_job(int(parts[2]), principal.name, principal.role == "admin")
            if len(parts) == 4 and parts[:2] == ["v1", "jobs"] and parts[3] == "priority" and self.command == "PATCH":
                principal = app.authenticate(self.headers)
                body = self._json_body()
                return 200, app.scheduler.update_priority(int(parts[2]), principal.name, int(body["priority"]))
            if len(parts) == 4 and parts[:2] == ["v1", "jobs"] and parts[3] == "cancel" and self.command == "POST":
                principal = app.authenticate(self.headers)
                return 200, app.scheduler.cancel_job(int(parts[2]), principal.name)
            if self.command == "GET" and path == "/v1/snapshot":
                principal = app.authenticate_client_or_admin(self.headers)
                return 200, app.scheduler.snapshot(redact_payloads=principal.role != "admin")
            if self.command == "POST" and path == "/v1/agent/register":
                app.authenticate(self.headers, "agent")
                body = self._json_body()
                return 200, app.scheduler.register_node(str(body["nodeId"]), str(body["hostname"]), body["gpus"], body.get("metadata"))
            if self.command == "POST" and path == "/v1/agent/heartbeat":
                app.authenticate(self.headers, "agent")
                body = self._json_body()
                return 200, app.scheduler.heartbeat_node(str(body["nodeId"]), body.get("gpus") or [])
            if self.command == "POST" and path == "/v1/agent/security/processes":
                app.authenticate(self.headers, "agent")
                body = self._json_body()
                return 200, app.scheduler.report_gpu_processes(str(body["nodeId"]), body.get("processes") or [])
            if len(parts) == 6 and parts[:3] == ["v1", "agent", "security"] and parts[3] == "events" and parts[5] == "termination-result" and self.command == "POST":
                app.authenticate(self.headers, "agent")
                body = self._json_body()
                return 200, app.scheduler.complete_process_termination(
                    str(body["nodeId"]), int(parts[4]), bool(body.get("success")), str(body.get("result") or ""))
            if self.command == "POST" and path == "/v1/agent/claim":
                app.authenticate(self.headers, "agent")
                body = self._json_body()
                return 200, app.scheduler.claim(str(body["nodeId"]), int(body.get("leaseSeconds", 45)))
            if len(parts) == 5 and parts[:2] == ["v1", "agent"] and parts[2] == "jobs" and parts[4] == "heartbeat" and self.command == "POST":
                app.authenticate(self.headers, "agent")
                body = self._json_body()
                return 200, app.scheduler.heartbeat_job(int(parts[3]), str(body["nodeId"]), str(body["leaseToken"]), int(body.get("leaseSeconds", 45)))
            if len(parts) == 5 and parts[:2] == ["v1", "agent"] and parts[2] == "jobs" and parts[4] == "finish" and self.command == "POST":
                app.authenticate(self.headers, "agent")
                body = self._json_body()
                return 200, app.scheduler.finish_job(
                    int(parts[3]), str(body["nodeId"]), str(body["leaseToken"]),
                    bool(body.get("success")), body.get("error"), body.get("result"))
            if self.command == "POST" and path == "/v1/agent/warm/released":
                app.authenticate(self.headers, "agent")
                body = self._json_body()
                return 200, app.scheduler.acknowledge_release(str(body["nodeId"]), str(body["profile"]), body.get("gpuIds") or [])
            if self.command == "POST" and path == "/v1/admin/warm/release":
                app.authenticate(self.headers, "admin")
                body = self._json_body()
                return 200, app.scheduler.request_warm_release(str(body["nodeId"]), str(body["profile"]))
            if self.command == "POST" and path == "/v1/admin/gpus/reconcile":
                app.authenticate(self.headers, "admin")
                body = self._json_body()
                return 200, app.scheduler.reconcile_gpus(str(body["nodeId"]), body.get("gpuIds") or [])
            if self.command == "GET" and path == "/v1/admin/services":
                app.authenticate(self.headers, "admin")
                return 200, {"items": app.scheduler.list_service_catalog()}
            if len(parts) == 5 and parts[:3] == ["v1", "admin", "services"] and parts[4] == "policy" and self.command == "PATCH":
                principal = app.authenticate(self.headers, "admin")
                body = self._json_body()
                actor = f"{principal.name}:{str(body.get('actor') or 'unknown')[:128]}"
                return 200, app.scheduler.update_service_policy(
                    parts[3], body.get("enabled"), body.get("defaultPriority"), actor)
            if self.command == "GET" and path == "/v1/admin/security-events":
                app.authenticate(self.headers, "admin")
                query = parse_qs(urlparse(self.path).query)
                status = query.get("status", [""])[0] or None
                limit = int(query.get("limit", ["100"])[0])
                return 200, {"items": app.scheduler.list_security_events(status, limit)}
            if len(parts) == 5 and parts[:3] == ["v1", "admin", "security-events"] and parts[4] == "acknowledge" and self.command == "POST":
                principal = app.authenticate(self.headers, "admin")
                body = self._json_body()
                actor = f"{principal.name}:{str(body.get('actor') or 'unknown')[:128]}"
                return 200, app.scheduler.acknowledge_security_event(int(parts[3]), actor)
            if len(parts) == 5 and parts[:3] == ["v1", "admin", "security-events"] and parts[4] == "terminate" and self.command == "POST":
                principal = app.authenticate(self.headers, "admin")
                body = self._json_body()
                actor = f"{principal.name}:{str(body.get('actor') or 'unknown')[:128]}"
                return 202, app.scheduler.request_process_termination(
                    int(parts[3]), actor, str(body.get("reason") or ""))
            if len(parts) == 5 and parts[:3] == ["v1", "admin", "jobs"] and parts[4] == "priority" and self.command == "PATCH":
                principal = app.authenticate(self.headers, "admin")
                body = self._json_body()
                return 200, app.scheduler.update_priority(int(parts[3]), principal.name, int(body["priority"]), admin=True)
            if len(parts) == 5 and parts[:3] == ["v1", "admin", "jobs"] and parts[4] == "cancel" and self.command == "POST":
                principal = app.authenticate(self.headers, "admin")
                return 200, app.scheduler.cancel_job(int(parts[3]), principal.name, admin=True)
            raise SchedulerError(404, "NOT_FOUND", "endpoint not found")

        def _handle(self) -> None:
            try:
                status, payload = self._dispatch()
                self._send(status, payload)
            except SchedulerError as exc:
                self._send(exc.status, {"error": {"code": exc.code, "message": str(exc)}})
            except (KeyError, TypeError, ValueError) as exc:
                self._send(400, {"error": {"code": "INVALID_REQUEST", "message": str(exc)}})
            except Exception as exc:
                self._send(500, {"error": {"code": "INTERNAL_ERROR", "message": str(exc)}})

        do_GET = _handle
        do_POST = _handle
        do_PATCH = _handle

    return Handler


def main() -> None:
    parser = argparse.ArgumentParser(description="AID GPU resource manager")
    parser.add_argument("--host", default=os.getenv("GPU_MANAGER_HOST", "127.0.0.1"))
    parser.add_argument("--port", type=int, default=int(os.getenv("GPU_MANAGER_PORT", "18120")))
    parser.add_argument("--db", default=os.getenv("GPU_MANAGER_DB", ".runtime/gpu-manager/gpu-manager.db"))
    parser.add_argument("--profiles", default=os.getenv("GPU_MANAGER_PROFILES"))
    parser.add_argument("--services", default=os.getenv("GPU_MANAGER_SERVICES"))
    parser.add_argument("--tls-cert", default=os.getenv("GPU_MANAGER_TLS_CERT"))
    parser.add_argument("--tls-key", default=os.getenv("GPU_MANAGER_TLS_KEY"))
    parser.add_argument("--allowed-cidrs", default=os.getenv("GPU_MANAGER_ALLOWED_CIDRS", ""))
    args = parser.parse_args()

    client_keys = parse_key_map(os.getenv("GPU_MANAGER_CLIENT_KEYS"))
    agent_token = os.getenv("GPU_MANAGER_AGENT_TOKEN", "")
    admin_token = os.getenv("GPU_MANAGER_ADMIN_TOKEN", "")
    if not client_keys or not agent_token or not admin_token:
        raise SystemExit("GPU_MANAGER_CLIENT_KEYS, GPU_MANAGER_AGENT_TOKEN and GPU_MANAGER_ADMIN_TOKEN are required")
    scheduler = GpuScheduler(Path(args.db), load_profiles(args.profiles), load_services(args.services))
    try:
        allowed_networks = parse_allowed_networks(args.allowed_cidrs)
    except ValueError as exc:
        raise SystemExit(f"invalid --allowed-cidrs: {exc}") from exc
    app = GpuManagerApplication(scheduler, client_keys, agent_token, admin_token, allowed_networks)
    server = ThreadingHTTPServer((args.host, args.port), make_handler(app))
    if bool(args.tls_cert) != bool(args.tls_key):
        raise SystemExit("both --tls-cert and --tls-key are required for HTTPS")
    scheme = "http"
    if args.tls_cert:
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.minimum_version = ssl.TLSVersion.TLSv1_2
        context.load_cert_chain(args.tls_cert, args.tls_key)
        server.socket = context.wrap_socket(server.socket, server_side=True)
        scheme = "https"
    print(f"AID GPU manager listening on {scheme}://{args.host}:{args.port}", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
