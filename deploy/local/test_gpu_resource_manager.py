import tempfile
import json
import threading
import time
import unittest
import urllib.request
from http.server import ThreadingHTTPServer
from pathlib import Path

from gpu_resource_manager import (
    GpuManagerApplication,
    GpuScheduler,
    SchedulerError,
    load_profiles,
    load_services,
    make_handler,
    parse_allowed_networks,
)


GPUS = [
    {"index": 0, "uuid": "GPU-0", "name": "RTX 3090", "totalVramMb": 24576},
    {"index": 1, "uuid": "GPU-1", "name": "RTX 3090", "totalVramMb": 24576},
]


class GpuSchedulerTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.scheduler = GpuScheduler(Path(self.temp.name) / "gpu.db", load_profiles(None))
        self.scheduler.register_node("node-222", "gpu222", GPUS)
        self.scheduler.services = [{
            "id": "fast-h3", "name": "FastH3", "profile": "h3-single-fast", "adapterReady": True,
        }, {
            "id": "pending-service", "name": "Pending", "profile": "qianchuan-render", "adapterReady": False,
        }]

    def tearDown(self):
        self.temp.cleanup()

    def submit(self, source="aid", profile="h3-dual-dense", priority=80, key=None):
        body = {"profile": profile, "priority": priority, "payload": {"prompt": "test"}}
        if key:
            body["idempotencyKey"] = key
        return self.scheduler.submit_job(source, body)

    def test_gpu_222_inventory_references_configured_disabled_profiles(self):
        root = Path(__file__).parent
        profiles = load_profiles(str(root / "gpu-resource-profiles.gpu-222.json"))
        services = load_services(str(root / "gpu-resource-services.gpu-222.json"))
        self.assertTrue(services)
        self.assertTrue(all(item["profile"] in profiles for item in services))
        self.assertTrue(all(not profiles[item["profile"]]["enabled"] for item in services))
        self.assertEqual([0], profiles["h3-standard-quality"]["gpuIndices"])
        self.assertEqual([1], profiles["h3-single-fast"]["gpuIndices"])
        self.assertEqual(21600, profiles["h3-single-fast"]["warmSeconds"])

    def test_source_cidr_parser_rejects_invalid_network(self):
        with self.assertRaises(ValueError):
            parse_allowed_networks("not-a-network")
        networks = parse_allowed_networks("127.0.0.1/32,10.190.0.0/24")
        self.assertEqual(2, len(networks))

    def test_application_source_allowlist_is_enforced(self):
        app = GpuManagerApplication(
            self.scheduler, {"key": "aid"}, "agent", "admin",
            parse_allowed_networks("10.190.0.0/24"),
        )
        app.authorize_address("10.190.0.203")
        with self.assertRaises(SchedulerError) as raised:
            app.authorize_address("10.191.0.20")
        self.assertEqual("SOURCE_NOT_ALLOWED", raised.exception.code)

    def test_dual_gpu_job_is_allocated_atomically(self):
        job = self.submit()
        claim = self.scheduler.claim("node-222")
        self.assertEqual("RUN_JOB", claim["action"])
        self.assertEqual(job["id"], claim["job"]["id"])
        self.assertEqual(["GPU-0", "GPU-1"], claim["job"]["assigned_gpu_ids"])
        self.assertFalse(claim["job"]["warm_hit"])

    def test_running_h3_blocks_single_gpu_jobs(self):
        self.submit()
        self.scheduler.claim("node-222")
        self.submit(source="hermes", profile="qianchuan-render", priority=100)
        self.assertEqual("WAIT", self.scheduler.claim("node-222")["action"])

    def test_warm_h3_has_affinity_and_release_is_acknowledged(self):
        first = self.submit(key="h3-1")
        claim = self.scheduler.claim("node-222")
        self.scheduler.finish_job(first["id"], "node-222", claim["leaseToken"], True)

        qianchuan = self.submit(source="hermes", profile="qianchuan-render", priority=100)
        second = self.submit(priority=80, key="h3-2")
        warm_claim = self.scheduler.claim("node-222")
        self.assertEqual(second["id"], warm_claim["job"]["id"])
        self.assertTrue(warm_claim["job"]["warm_hit"])
        self.scheduler.finish_job(second["id"], "node-222", warm_claim["leaseToken"], True)

        requested = self.scheduler.request_warm_release("node-222", "h3-dual-dense")
        self.assertEqual(2, requested["releaseRequested"])
        release = self.scheduler.claim("node-222")
        self.assertEqual("RELEASE_WARM", release["action"])
        self.scheduler.acknowledge_release("node-222", "h3-dual-dense", release["gpuIds"])
        next_claim = self.scheduler.claim("node-222")
        self.assertEqual(qianchuan["id"], next_claim["job"]["id"])

    def test_idempotency_returns_same_job(self):
        first = self.submit(key="same-request")
        second = self.submit(key="same-request")
        self.assertEqual(first["id"], second["id"])
        self.assertEqual(1, len(self.scheduler.list_queue()))

    def test_worker_result_is_returned_to_job_owner(self):
        job = self.submit()
        claim = self.scheduler.claim("node-222")
        finished = self.scheduler.finish_job(
            job["id"], "node-222", claim["leaseToken"], True,
            result={"resultRef": "media://project-26/shot-18.mp4"},
        )
        self.assertEqual(
            "media://project-26/shot-18.mp4", finished["result"]["resultRef"])

    def test_client_cannot_reprioritize_another_sources_job(self):
        job = self.submit(source="aid")
        with self.assertRaises(SchedulerError) as raised:
            self.scheduler.update_priority(job["id"], "hermes", 5)
        self.assertEqual("JOB_FORBIDDEN", raised.exception.code)

    def test_priority_change_only_applies_before_start(self):
        job = self.submit()
        updated = self.scheduler.update_priority(job["id"], "aid", 20)
        self.assertEqual(20, updated["priority"])
        self.scheduler.claim("node-222")
        with self.assertRaises(SchedulerError) as raised:
            self.scheduler.update_priority(job["id"], "aid", 10)
        self.assertEqual("JOB_ALREADY_STARTED", raised.exception.code)

    def test_client_priority_cannot_exceed_administrator_cap(self):
        profile = self.scheduler.profiles["h3-dual-dense"]
        profile["defaultPriority"] = 80
        profile["maxClientPriority"] = 80
        with self.assertRaises(SchedulerError) as raised:
            self.submit(priority=81)
        self.assertEqual("PRIORITY_NOT_ALLOWED", raised.exception.code)

        job = self.submit(priority=80)
        with self.assertRaises(SchedulerError) as raised:
            self.scheduler.update_priority(job["id"], "aid", 81)
        self.assertEqual("PRIORITY_NOT_ALLOWED", raised.exception.code)

        updated = self.scheduler.update_priority(job["id"], "admin", 100, admin=True)
        self.assertEqual(100, updated["priority"])

    def test_expired_lease_quarantines_gpus_instead_of_reusing_them(self):
        job = self.submit()
        self.scheduler.claim("node-222", lease_seconds=15)
        with self.scheduler._transaction() as conn:
            conn.execute("UPDATE jobs SET lease_expires_at=? WHERE id=?", (time.time() - 1, job["id"]))
        self.submit(source="hermes", profile="qianchuan-render", priority=100)
        self.assertEqual("WAIT", self.scheduler.claim("node-222")["action"])
        snapshot = self.scheduler.snapshot()
        self.assertTrue(all(gpu["state"] == "QUARANTINED" for gpu in snapshot["nodes"][0]["gpus"]))
        self.assertEqual("LOST", self.scheduler.get_job(job["id"])["status"])

    def test_unauthorized_gpu_process_creates_deduplicated_security_event(self):
        process = {
            "gpuId": "GPU-0", "pid": 4321, "processStartId": "991",
            "processName": "python", "username": "hermes", "usedVramMb": 8192,
            "commandLine": "python rogue.py", "cgroup": "/user.slice/hermes",
        }
        first = self.scheduler.report_gpu_processes("node-222", [process])
        second = self.scheduler.report_gpu_processes("node-222", [process])
        self.assertEqual(1, first["unauthorizedCount"])
        self.assertEqual([], second["terminationRequests"])
        events = self.scheduler.list_security_events("OPEN")
        self.assertEqual(1, len(events))
        self.assertEqual(2, events[0]["occurrence_count"])
        self.assertIn("没有有效使用租约", events[0]["detected_reason"])

    def test_authorized_job_process_does_not_create_security_event(self):
        job = self.submit()
        self.scheduler.claim("node-222")
        self.scheduler.report_gpu_processes("node-222", [{
            "gpuId": "GPU-0", "pid": 4321, "processStartId": "991",
            "processName": "python", "matchedJobId": job["id"],
        }])
        self.assertEqual([], self.scheduler.list_security_events())

    def test_low_vram_idle_infrastructure_is_authorized_without_a_lease(self):
        self.scheduler.report_gpu_processes("node-222", [{
            "gpuId": "GPU-0", "pid": 4321, "processStartId": "991",
            "processName": "python", "usedVramMb": 256,
            "matchedInfrastructure": True,
            "matchedProfile": "h3-single-fast",
            "infrastructureMaxIdleVramMb": 512,
        }])
        self.assertEqual([], self.scheduler.list_security_events())

    def test_idle_infrastructure_over_vram_limit_is_not_authorized(self):
        self.scheduler.report_gpu_processes("node-222", [{
            "gpuId": "GPU-0", "pid": 4321, "processStartId": "991",
            "processName": "python", "usedVramMb": 2048,
            "matchedInfrastructure": True,
            "matchedProfile": "h3-single-fast",
            "infrastructureMaxIdleVramMb": 512,
        }])
        self.assertEqual(1, len(self.scheduler.list_security_events("OPEN")))

    def test_open_security_event_blocks_new_gpu_lease_until_process_resolves(self):
        self.scheduler.profiles["qianchuan-render"]["gpuIndices"] = [0]
        process = {
            "gpuId": "GPU-0", "pid": 4321, "processStartId": "991",
            "processName": "python", "username": "legacy", "usedVramMb": 1024,
        }
        self.scheduler.report_gpu_processes("node-222", [process])
        self.submit(profile="qianchuan-render")
        self.assertEqual("WAIT", self.scheduler.claim("node-222")["action"])

        self.scheduler.report_gpu_processes("node-222", [])
        claim = self.scheduler.claim("node-222")
        self.assertEqual("RUN_JOB", claim["action"])
        self.assertEqual(["GPU-0"], claim["job"]["assigned_gpu_ids"])

    def test_admin_termination_request_is_delivered_to_agent_and_audited(self):
        process = {
            "gpuId": "GPU-0", "pid": 4321, "processStartId": "991",
            "processName": "python", "username": "hermes",
        }
        self.scheduler.report_gpu_processes("node-222", [process])
        event = self.scheduler.list_security_events("OPEN")[0]
        requested = self.scheduler.request_process_termination(event["id"], "admin", "绕过调度器占用 GPU")
        self.assertEqual("TERMINATION_REQUESTED", requested["status"])
        report = self.scheduler.report_gpu_processes("node-222", [process])
        self.assertEqual(event["id"], report["terminationRequests"][0]["eventId"])
        completed = self.scheduler.complete_process_termination(
            "node-222", event["id"], True, "SIGTERM 已结束越权进程")
        self.assertEqual("TERMINATED", completed["status"])

    def test_service_policy_controls_default_priority_and_enablement(self):
        self.scheduler.profiles["h3-single-fast"]["maxClientPriority"] = 50
        updated = self.scheduler.update_service_policy("fast-h3", True, 88, "admin")
        self.assertTrue(updated["enabled"])
        self.assertEqual(88, updated["defaultPriority"])
        job = self.scheduler.submit_job("aid", {"profile": "h3-single-fast", "payload": {}})
        self.assertEqual(88, job["priority"])
        with self.assertRaises(SchedulerError) as raised:
            self.scheduler.submit_job("aid", {
                "profile": "h3-single-fast", "priority": 89, "payload": {},
            })
        self.assertEqual("PRIORITY_NOT_ALLOWED", raised.exception.code)
        self.scheduler.update_service_policy("fast-h3", False, None, "admin")
        with self.assertRaises(SchedulerError) as raised:
            self.scheduler.submit_job("aid", {"profile": "h3-single-fast", "payload": {}})
        self.assertEqual("PROFILE_DISABLED", raised.exception.code)

    def test_service_cannot_enable_before_fixed_adapter_is_ready(self):
        with self.assertRaises(SchedulerError) as raised:
            self.scheduler.update_service_policy("pending-service", True, 50, "admin")
        self.assertEqual("SERVICE_ADAPTER_NOT_READY", raised.exception.code)

    def test_profile_gpu_indices_are_enforced(self):
        self.scheduler.profiles["h3-single-fast"]["gpuIndices"] = [1]
        job = self.scheduler.submit_job("aid", {"profile": "h3-single-fast", "payload": {}})
        claim = self.scheduler.claim("node-222")
        self.assertEqual(job["id"], claim["job"]["id"])
        self.assertEqual(["GPU-1"], claim["job"]["assigned_gpu_ids"])

    def test_warm_gpu_does_not_block_job_pinned_to_other_gpu(self):
        self.scheduler.profiles["h3-single-fast"]["gpuIndices"] = [1]
        fast = self.scheduler.submit_job("aid", {
            "profile": "h3-single-fast", "payload": {}, "idempotencyKey": "fast-warm",
        })
        fast_claim = self.scheduler.claim("node-222")
        self.scheduler.finish_job(
            fast["id"], "node-222", fast_claim["leaseToken"], True,
        )
        self.scheduler.profiles["h3-standard-quality"] = {
            **self.scheduler.profiles["h3-single-fast"],
            "gpuIndices": [0],
            "warmKey": "minimax-h3:standard",
        }
        standard = self.scheduler.submit_job("aid", {
            "profile": "h3-standard-quality", "payload": {},
            "idempotencyKey": "standard-on-other-gpu",
        })

        standard_claim = self.scheduler.claim("node-222")

        self.assertEqual("RUN_JOB", standard_claim["action"])
        self.assertEqual(standard["id"], standard_claim["job"]["id"])
        self.assertEqual(["GPU-0"], standard_claim["job"]["assigned_gpu_ids"])


class GpuManagerHttpTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        scheduler = GpuScheduler(Path(self.temp.name) / "http.db", load_profiles(None), [{
            "id": "fast-h3", "name": "FastH3", "profile": "h3-single-fast", "adapterReady": True,
        }])
        app = GpuManagerApplication(
            scheduler,
            client_keys={"client-secret": "hermes", "aid-secret": "aid"},
            agent_token="agent-secret",
            admin_token="admin-secret",
        )
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), make_handler(app))
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.base = f"http://127.0.0.1:{self.server.server_port}"

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)
        self.temp.cleanup()

    def request(self, method, path, token=None, body=None):
        data = None if body is None else json.dumps(body).encode()
        headers = {"Content-Type": "application/json"}
        if token:
            headers["Authorization"] = f"Bearer {token}"
        req = urllib.request.Request(self.base + path, data=data, headers=headers, method=method)
        with urllib.request.urlopen(req, timeout=5) as response:
            return response.status, json.load(response)

    def test_external_client_must_submit_then_agent_claims(self):
        status, _ = self.request("POST", "/v1/agent/register", "agent-secret", {
            "nodeId": "node-222", "hostname": "gpu222", "gpus": GPUS,
        })
        self.assertEqual(200, status)
        status, job = self.request("POST", "/v1/jobs", "client-secret", {
            "profile": "h3-dual-dense", "priority": 80,
            "idempotencyKey": "hermes-shot-1", "payload": {"shot": 1},
        })
        self.assertEqual(202, status)
        self.assertEqual("hermes", job["source"])
        status, claim = self.request("POST", "/v1/agent/claim", "agent-secret", {
            "nodeId": "node-222",
        })
        self.assertEqual(200, status)
        self.assertEqual("RUN_JOB", claim["action"])
        self.assertEqual(job["id"], claim["job"]["id"])

    def test_snapshot_rejects_missing_token(self):
        with self.assertRaises(urllib.error.HTTPError) as raised:
            self.request("GET", "/v1/snapshot")
        self.assertEqual(401, raised.exception.code)
        raised.exception.close()

    def test_client_cannot_read_another_clients_job(self):
        _, job = self.request("POST", "/v1/jobs", "client-secret", {
            "profile": "qianchuan-render", "priority": 50,
            "idempotencyKey": "private-hermes-job", "payload": {"private": "value"},
        })
        with self.assertRaises(urllib.error.HTTPError) as raised:
            self.request("GET", f"/v1/jobs/{job['id']}", "aid-secret")
        self.assertEqual(403, raised.exception.code)
        raised.exception.close()

    def test_client_snapshot_redacts_job_payload(self):
        self.request("POST", "/v1/jobs", "client-secret", {
            "profile": "qianchuan-render", "priority": 50,
            "idempotencyKey": "redacted-job", "payload": {"secret": "value"},
        })
        _, snapshot = self.request("GET", "/v1/snapshot", "client-secret")
        self.assertNotIn("payload", snapshot["queued"][0])

    def test_security_events_are_admin_only(self):
        self.request("POST", "/v1/agent/register", "agent-secret", {
            "nodeId": "node-222", "hostname": "gpu222", "gpus": GPUS,
        })
        self.request("POST", "/v1/agent/security/processes", "agent-secret", {
            "nodeId": "node-222", "processes": [{
                "gpuId": "GPU-0", "pid": 555, "processStartId": "100",
                "processName": "python", "username": "hermes",
            }],
        })
        with self.assertRaises(urllib.error.HTTPError) as raised:
            self.request("GET", "/v1/admin/security-events", "client-secret")
        self.assertEqual(401, raised.exception.code)
        raised.exception.close()
        status, payload = self.request("GET", "/v1/admin/security-events?status=OPEN", "admin-secret")
        self.assertEqual(200, status)
        self.assertEqual(1, len(payload["items"]))

    def test_admin_can_request_process_termination_over_http(self):
        self.request("POST", "/v1/agent/register", "agent-secret", {
            "nodeId": "node-222", "hostname": "gpu222", "gpus": GPUS,
        })
        process = {
            "gpuId": "GPU-0", "pid": 777, "processStartId": "101",
            "processName": "python", "username": "hermes",
        }
        self.request("POST", "/v1/agent/security/processes", "agent-secret", {
            "nodeId": "node-222", "processes": [process],
        })
        _, payload = self.request("GET", "/v1/admin/security-events?status=OPEN", "admin-secret")
        event_id = payload["items"][0]["id"]
        status, event = self.request("POST", f"/v1/admin/security-events/{event_id}/terminate", "admin-secret", {
            "actor": "operator-a", "reason": "确认绕过统一API直接占用GPU",
        })
        self.assertEqual(202, status)
        self.assertEqual("gpu-admin:operator-a", event["termination_requested_by"])
        _, report = self.request("POST", "/v1/agent/security/processes", "agent-secret", {
            "nodeId": "node-222", "processes": [process],
        })
        self.assertEqual(event_id, report["terminationRequests"][0]["eventId"])

    def test_admin_can_list_and_update_gpu_service_policy(self):
        status, payload = self.request("GET", "/v1/admin/services", "admin-secret")
        self.assertEqual(200, status)
        self.assertEqual("fast-h3", payload["items"][0]["id"])
        status, updated = self.request("PATCH", "/v1/admin/services/fast-h3/policy", "admin-secret", {
            "actor": "operator-a", "enabled": True, "defaultPriority": 92,
        })
        self.assertEqual(200, status)
        self.assertTrue(updated["enabled"])
        self.assertEqual(92, updated["defaultPriority"])


if __name__ == "__main__":
    unittest.main()
