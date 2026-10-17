#!/usr/bin/env python3
"""GPU node agent for the AID GPU resource manager.

Only commands from this node-local allow-list are executable.  Client payloads are
written to a job file and are never interpolated into a shell command.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import signal
import socket
import ssl
import subprocess
import threading
import time
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any

if os.name != "nt":
    import pwd

try:
    import psutil
except ImportError:  # Optional on Linux; required for Windows process inspection.
    psutil = None

PROCESS_INSPECTION_ERRORS = (
    FileNotFoundError, ProcessLookupError, PermissionError, ValueError, IndexError,
) + ((psutil.Error,) if psutil is not None else ())


def api_json(base_url: str, token: str, path: str, payload: dict[str, Any] | None = None,
             ca_file: str | None = None) -> Any:
    data = None if payload is None else json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(
        base_url.rstrip("/") + path,
        data=data,
        headers={"Authorization": f"Bearer {token}", "Content-Type": "application/json"},
    )
    context = ssl.create_default_context(cafile=ca_file) if ca_file else None
    with urllib.request.urlopen(req, timeout=30, context=context) as response:
        return json.load(response)


def discover_gpus() -> list[dict[str, Any]]:
    query = "index,uuid,name,memory.total"
    output = subprocess.check_output(
        ["nvidia-smi", f"--query-gpu={query}", "--format=csv,noheader,nounits"],
        text=True,
    )
    result = []
    for line in output.splitlines():
        index, uuid, name, total = [value.strip() for value in line.split(",", 3)]
        result.append({"index": int(index), "uuid": uuid, "name": name, "totalVramMb": int(float(total))})
    if not result:
        raise RuntimeError("nvidia-smi returned no GPUs")
    return result


def gpu_metrics() -> list[dict[str, Any]]:
    query = "index,uuid,utilization.gpu,memory.used,temperature.gpu,power.draw"
    output = subprocess.check_output(
        ["nvidia-smi", f"--query-gpu={query}", "--format=csv,noheader,nounits"],
        text=True,
    )
    result = []
    for line in output.splitlines():
        index, uuid, util, memory, temperature, power = [value.strip() for value in line.split(",", 5)]
        result.append({
            "index": int(index),
            "uuid": uuid,
            "metrics": {
                "utilizationPercent": float(util),
                "memoryUsedMb": float(memory),
                "temperatureC": float(temperature),
                "powerW": float(power),
            },
        })
    return result


def _proc_details(pid: int) -> dict[str, Any]:
    if os.name == "nt":
        if psutil is None:
            raise RuntimeError("psutil is required for Windows GPU process inspection")
        process = psutil.Process(pid)
        return {
            "processStartId": f"{process.create_time():.6f}",
            "processGroupId": pid,
            "username": process.username(),
            "commandLine": subprocess.list2cmdline(process.cmdline()),
            "cgroup": "",
        }
    proc = Path("/proc") / str(pid)
    stat = (proc / "stat").read_text(encoding="utf-8", errors="replace")
    rest = stat[stat.rfind(")") + 2:].split()
    status = (proc / "status").read_text(encoding="utf-8", errors="replace")
    uid_line = next((line for line in status.splitlines() if line.startswith("Uid:")), "Uid:\t-1")
    uid = int(uid_line.split()[1])
    try:
        username = pwd.getpwuid(uid).pw_name
    except KeyError:
        username = str(uid)
    command = (proc / "cmdline").read_bytes().replace(b"\0", b" ").decode("utf-8", "replace").strip()
    cgroup = (proc / "cgroup").read_text(encoding="utf-8", errors="replace").strip()
    return {
        "processStartId": rest[19],
        "processGroupId": int(rest[2]),
        "username": username,
        "commandLine": command,
        "cgroup": cgroup,
    }


def discover_gpu_processes() -> list[dict[str, Any]]:
    query = "gpu_uuid,pid,process_name,used_gpu_memory"
    output = subprocess.check_output(
        ["nvidia-smi", f"--query-compute-apps={query}", "--format=csv,noheader,nounits"],
        text=True,
    )
    result: list[dict[str, Any]] = []
    for line in output.splitlines():
        if not line.strip():
            continue
        gpu_id, raw_pid, process_name, raw_memory = [value.strip() for value in line.split(",", 3)]
        pid = int(raw_pid)
        try:
            details = _proc_details(pid)
        except PROCESS_INSPECTION_ERRORS:
            continue
        try:
            memory = float(raw_memory)
        except ValueError:
            memory = 0.0
        result.append({
            "gpuId": gpu_id,
            "pid": pid,
            "processName": process_name,
            "usedVramMb": memory,
            **details,
        })
    return result


def run_fixed(command: list[str], env: dict[str, str] | None = None, timeout: int | None = None) -> subprocess.CompletedProcess[str]:
    if not command or not all(isinstance(value, str) and value for value in command):
        raise ValueError("allow-listed command must be a non-empty string array")
    return subprocess.run(command, check=False, text=True, capture_output=True, env=env, timeout=timeout)


def worker_result(stdout: str, required: bool = False) -> dict[str, Any] | None:
    lines = [line.strip() for line in stdout.splitlines() if line.strip()]
    if not lines:
        if required:
            raise RuntimeError("worker returned no result JSON")
        return None
    try:
        value = json.loads(lines[-1])
    except json.JSONDecodeError as exc:
        if required:
            raise RuntimeError("worker final output is not result JSON") from exc
        return None
    if not isinstance(value, dict):
        raise RuntimeError("worker result JSON must be an object")
    if len(json.dumps(value, ensure_ascii=False).encode("utf-8")) > 1_048_576:
        raise RuntimeError("worker result JSON exceeds 1 MiB")
    return value


class NodeAgent:
    def __init__(self, config: dict[str, Any], dry_run: bool = False):
        self.config = config
        self.base_url = str(config["managerUrl"])
        token_file = str(config.get("agentTokenFile") or "").strip()
        self.token = str(config.get("agentToken") or "").strip()
        if token_file:
            self.token = Path(token_file).read_text(encoding="utf-8").strip()
        if not self.token:
            raise ValueError("agentToken or agentTokenFile is required")
        self.ca_file = str(config.get("managerCaFile") or "") or None
        self.node_id = str(config.get("nodeId") or socket.gethostname())
        self.hostname = str(config.get("hostname") or socket.gethostname())
        self.poll_seconds = max(1.0, float(config.get("pollSeconds", 2)))
        self.spool_dir = Path(config.get("spoolDir") or ".runtime/gpu-agent")
        self.spool_dir.mkdir(parents=True, exist_ok=True)
        self.profiles = config.get("profiles") or {}
        self.idle_infrastructure = config.get("idleInfrastructure") or []
        self.dry_run = dry_run
        self.execution_mode = str(config.get("executionMode") or "enforce").strip().lower()
        if self.execution_mode not in {"observe", "enforce"}:
            raise ValueError("executionMode must be observe or enforce")
        self.gpus = discover_gpus()
        self.gpu_by_uuid = {item["uuid"]: item for item in self.gpus}
        self.active: dict[int, threading.Thread] = {}
        self.authorized_jobs: dict[int, dict[str, Any]] = {}
        self.active_profiles: set[str] = set()
        self._active_lock = threading.Lock()

    def register(self) -> None:
        snapshot = api_json(self.base_url, self.token, "/v1/agent/register", {
            "nodeId": self.node_id,
            "hostname": self.hostname,
            "gpus": self.gpus,
            "metadata": {
                "agentVersion": "1", "dryRun": self.dry_run,
                "executionMode": self.execution_mode,
            },
        }, ca_file=self.ca_file)
        with self._active_lock:
            self.active_profiles.update(
                str(gpu["warm_profile"])
                for gpu in snapshot.get("gpus") or []
                if gpu.get("state") in {"WARM", "RELEASING"} and gpu.get("warm_profile")
            )

    def heartbeat_node(self) -> None:
        api_json(self.base_url, self.token, "/v1/agent/heartbeat", {
            "nodeId": self.node_id,
            "gpus": gpu_metrics(),
        }, ca_file=self.ca_file)

    def _profile(self, name: str) -> dict[str, Any]:
        value = self.profiles.get(name)
        if not isinstance(value, dict):
            raise RuntimeError(f"profile {name!r} is not configured on this node")
        return value

    def _job_file(self, job: dict[str, Any], gpu_ids: list[str]) -> Path:
        path = self.spool_dir / f"job-{job['id']}.json"
        content = {
            "jobId": job["id"],
            "source": job["source"],
            "profile": job["profile"],
            "payload": job.get("payload") or {},
            "gpuIds": gpu_ids,
        }
        path.write_text(json.dumps(content, ensure_ascii=False, indent=2), encoding="utf-8")
        return path

    @staticmethod
    def _materialize(command: list[str], job_file: Path) -> list[str]:
        return [str(job_file) if value == "{job_file}" else value for value in command]

    def _start_profile(self, profile_name: str, profile: dict[str, Any]) -> None:
        command = profile.get("startCommand") or []
        if not command or self.dry_run:
            return
        result = run_fixed(command, timeout=int(profile.get("startTimeoutSeconds", 180)))
        if result.returncode != 0:
            raise RuntimeError(f"profile start failed: {result.stderr[-500:]}")
        with self._active_lock:
            self.active_profiles.add(profile_name)

    def _stop_profile(self, profile_name: str) -> None:
        profile = self._profile(profile_name)
        command = profile.get("stopCommand") or []
        if not command or self.dry_run:
            return
        result = run_fixed(command, timeout=int(profile.get("stopTimeoutSeconds", 180)))
        if result.returncode != 0:
            raise RuntimeError(f"profile stop failed: {result.stderr[-500:]}")
        with self._active_lock:
            self.active_profiles.discard(profile_name)

    def _annotate_authorization(self, processes: list[dict[str, Any]]) -> list[dict[str, Any]]:
        with self._active_lock:
            jobs = {job_id: dict(value) for job_id, value in self.authorized_jobs.items()}
            active_profiles = set(self.active_profiles)
        for process in processes:
            gpu_id = process["gpuId"]
            pgid = process.get("processGroupId")
            for job_id, value in jobs.items():
                if gpu_id in value["gpuIds"] and pgid == value.get("processGroupId"):
                    process["matchedJobId"] = job_id
                    process["matchedProfile"] = value["profile"]
                    break
            if process.get("matchedJobId") is not None:
                continue
            searchable = "\n".join((
                str(process.get("processName") or ""),
                str(process.get("commandLine") or ""),
                str(process.get("cgroup") or ""),
            ))
            for profile_name in active_profiles:
                profile = self.profiles.get(profile_name) or {}
                patterns = profile.get("allowedProcessPatterns") or []
                if any(re.search(str(pattern), searchable) for pattern in patterns):
                    process["matchedProfile"] = profile_name
                    break
            if process.get("matchedJobId") is not None or process.get("matchedProfile"):
                continue
            used_vram = max(0.0, float(process.get("usedVramMb") or 0))
            for rule in self.idle_infrastructure:
                if not isinstance(rule, dict):
                    continue
                patterns = rule.get("allowedProcessPatterns") or []
                limit = max(0.0, float(rule.get("maxIdleVramMb") or 0))
                if limit > 0 and used_vram <= limit and any(
                    re.search(str(pattern), searchable) for pattern in patterns
                ):
                    process["matchedInfrastructure"] = True
                    process["matchedProfile"] = str(rule.get("profile") or "") or None
                    process["infrastructureMaxIdleVramMb"] = limit
                    break
        return processes

    @staticmethod
    def _same_process(pid: int, process_start_id: str) -> bool:
        if pid <= 1 or pid == os.getpid():
            return False
        try:
            return str(_proc_details(pid)["processStartId"]) == str(process_start_id)
        except PROCESS_INSPECTION_ERRORS:
            return False

    def _terminate_unauthorized(self, request: dict[str, Any]) -> tuple[bool, str]:
        if self.execution_mode != "enforce":
            return False, "节点代理处于observe模式，未发送终止信号"
        pid = int(request["pid"])
        start_id = str(request["processStartId"])
        observed = {(item["pid"], str(item["processStartId"])) for item in discover_gpu_processes()}
        if (pid, start_id) not in observed:
            return True, "进程已自行退出或不再占用GPU，无需发送信号"
        if not self._same_process(pid, start_id):
            return False, "PID 已失效或启动标识变化，拒绝终止以避免误杀"
        if os.name == "nt":
            if psutil is None:
                return False, "Windows节点缺少psutil，拒绝终止进程"
            process = psutil.Process(pid)
            process.terminate()
        else:
            os.kill(pid, signal.SIGTERM)
        deadline = time.monotonic() + max(1.0, float(self.config.get("terminationGraceSeconds", 5)))
        while time.monotonic() < deadline:
            if not self._same_process(pid, start_id):
                return True, "SIGTERM 已结束越权进程"
            time.sleep(0.2)
        if not self._same_process(pid, start_id):
            return True, "SIGTERM 已结束越权进程"
        if os.name == "nt":
            psutil.Process(pid).kill()
            return True, "终止超时后已强制结束Windows进程"
        os.kill(pid, signal.SIGKILL)
        return True, "SIGTERM 超时后已发送 SIGKILL"

    def report_security(self) -> None:
        processes = self._annotate_authorization(discover_gpu_processes())
        response = api_json(self.base_url, self.token, "/v1/agent/security/processes", {
            "nodeId": self.node_id,
            "processes": processes,
        }, ca_file=self.ca_file)
        for request in response.get("terminationRequests") or []:
            try:
                success, result = self._terminate_unauthorized(request)
            except Exception as exc:
                success, result = False, f"{type(exc).__name__}: {exc}"
            api_json(
                self.base_url,
                self.token,
                f"/v1/agent/security/events/{int(request['eventId'])}/termination-result",
                {"nodeId": self.node_id, "success": success, "result": result},
                ca_file=self.ca_file,
            )

    def _lease_heartbeat(self, stop: threading.Event, job_id: int, lease_token: str) -> None:
        while not stop.wait(10):
            try:
                api_json(self.base_url, self.token, f"/v1/agent/jobs/{job_id}/heartbeat", {
                    "nodeId": self.node_id,
                    "leaseToken": lease_token,
                    "leaseSeconds": 45,
                }, ca_file=self.ca_file)
            except Exception as exc:
                print(f"lease heartbeat failed for job {job_id}: {exc}", flush=True)

    def _execute_job(self, claim: dict[str, Any]) -> None:
        job = claim["job"]
        job_id = int(job["id"])
        profile_name = str(job["profile"])
        lease_token = str(claim["leaseToken"])
        gpu_ids = list(job.get("assigned_gpu_ids") or [])
        stop = threading.Event()
        heart = threading.Thread(target=self._lease_heartbeat, args=(stop, job_id, lease_token), daemon=True)
        success = False
        error: str | None = None
        result_payload: dict[str, Any] | None = None
        try:
            profile = self._profile(profile_name)
            if not claim.get("job", {}).get("warm_hit"):
                self._start_profile(profile_name, profile)
            job_file = self._job_file(job, gpu_ids)
            command = self._materialize(profile.get("runCommand") or [], job_file)
            if not command:
                raise RuntimeError(f"profile {profile_name!r} has no runCommand")
            heart.start()
            if self.dry_run:
                time.sleep(float(profile.get("dryRunSeconds", 0.1)))
                success = True
            else:
                indices = [str(self.gpu_by_uuid[gpu_id]["index"]) for gpu_id in gpu_ids]
                env = os.environ.copy()
                env["CUDA_VISIBLE_DEVICES"] = ",".join(indices)
                env["GPU_RESOURCE_IDS"] = ",".join(gpu_ids)
                env["GPU_JOB_ID"] = str(job_id)
                process = subprocess.Popen(
                    command, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                    env=env, start_new_session=True,
                )
                with self._active_lock:
                    self.active_profiles.add(profile_name)
                    self.authorized_jobs[job_id] = {
                        "processGroupId": process.pid,
                        "profile": profile_name,
                        "gpuIds": gpu_ids,
                    }
                try:
                    stdout, stderr = process.communicate(timeout=int(claim["profileConfig"]["maxRunSeconds"]))
                except subprocess.TimeoutExpired:
                    os.killpg(process.pid, signal.SIGKILL)
                    stdout, stderr = process.communicate()
                    raise RuntimeError("worker command exceeded maxRunSeconds")
                success = process.returncode == 0
                if not success:
                    error = (stderr or stdout or "worker command failed")[-1_000:]
                else:
                    result_payload = worker_result(
                        stdout or "", required=bool(profile.get("resultRequired", False)))
        except Exception as exc:
            error = f"{type(exc).__name__}: {exc}"
        finally:
            stop.set()
            if heart.is_alive():
                heart.join(timeout=2)
            try:
                keep_warm = success and int(claim.get("profileConfig", {}).get("warmSeconds", 0)) > 0
                if not keep_warm:
                    try:
                        self._stop_profile(profile_name)
                    except Exception as cleanup_exc:
                        success = False
                        detail = f"profile cleanup failed: {type(cleanup_exc).__name__}: {cleanup_exc}"
                        error = f"{error}; {detail}" if error else detail
                api_json(self.base_url, self.token, f"/v1/agent/jobs/{job_id}/finish", {
                    "nodeId": self.node_id,
                    "leaseToken": lease_token,
                    "success": success,
                    "error": error,
                    "result": result_payload,
                }, ca_file=self.ca_file)
            finally:
                with self._active_lock:
                    self.active.pop(job_id, None)
                    self.authorized_jobs.pop(job_id, None)
                    if int(claim.get("profileConfig", {}).get("warmSeconds", 0)) <= 0:
                        self.active_profiles.discard(profile_name)

    def _launch(self, claim: dict[str, Any]) -> None:
        job_id = int(claim["job"]["id"])
        thread = threading.Thread(target=self._execute_job, args=(claim,), name=f"gpu-job-{job_id}", daemon=True)
        with self._active_lock:
            self.active[job_id] = thread
        thread.start()

    def _release(self, action: dict[str, Any]) -> None:
        profile = str(action["profile"])
        self._stop_profile(profile)
        api_json(self.base_url, self.token, "/v1/agent/warm/released", {
            "nodeId": self.node_id,
            "profile": profile,
            "gpuIds": action["gpuIds"],
        }, ca_file=self.ca_file)

    def run_forever(self) -> None:
        self.register()
        last_heartbeat = 0.0
        while True:
            try:
                now = time.monotonic()
                if now - last_heartbeat >= 5:
                    self.heartbeat_node()
                    self.report_security()
                    last_heartbeat = now
                if self.execution_mode == "observe":
                    time.sleep(self.poll_seconds)
                    continue
                action = api_json(self.base_url, self.token, "/v1/agent/claim", {
                    "nodeId": self.node_id,
                    "leaseSeconds": 45,
                }, ca_file=self.ca_file)
                if action.get("action") == "RUN_JOB":
                    self._launch(action)
                elif action.get("action") == "RELEASE_WARM":
                    self._release(action)
                else:
                    time.sleep(self.poll_seconds)
            except urllib.error.HTTPError as exc:
                print(f"manager HTTP {exc.code}: {exc.read().decode('utf-8', 'replace')}", flush=True)
                time.sleep(5)
            except Exception as exc:
                print(f"agent loop error: {type(exc).__name__}: {exc}", flush=True)
                time.sleep(5)


def main() -> None:
    parser = argparse.ArgumentParser(description="AID GPU node agent")
    parser.add_argument("--config", required=True)
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()
    config = json.loads(Path(args.config).read_text(encoding="utf-8"))
    NodeAgent(config, dry_run=args.dry_run).run_forever()


if __name__ == "__main__":
    main()
