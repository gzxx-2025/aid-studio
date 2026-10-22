import json
import os
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from unittest.mock import patch

try:
    from .gpu_scheduler_client import GpuSchedulerClient, SchedulerApiError
except ImportError:
    from gpu_scheduler_client import GpuSchedulerClient, SchedulerApiError


class FakeHandler(BaseHTTPRequestHandler):
    requests = []
    job_status = "QUEUED"

    def log_message(self, *_args):
        return

    def send_json(self, status, payload):
        raw = json.dumps(payload).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def handle_request(self):
        length = int(self.headers.get("Content-Length") or 0)
        body = json.loads(self.rfile.read(length) or b"{}")
        self.__class__.requests.append((self.command, self.path, self.headers, body))
        if self.headers.get("Authorization") != "Bearer test-key":
            return self.send_json(401, {"error": {"code": "UNAUTHORIZED", "message": "bad key"}})
        if self.path == "/v1/profiles":
            return self.send_json(200, {"h3-single-fast": {"enabled": True}})
        if self.path == "/v1/jobs" and self.command == "POST":
            return self.send_json(202, {"id": 7, "status": "QUEUED", "queuePosition": 1})
        if self.path == "/v1/jobs/7" and self.command == "GET":
            return self.send_json(200, {"id": 7, "status": self.__class__.job_status})
        if self.path == "/v1/jobs/7/priority" and self.command == "PATCH":
            return self.send_json(403, {"error": {
                "code": "PRIORITY_NOT_ALLOWED", "message": "administrator cap"
            }})
        return self.send_json(404, {"error": {"code": "NOT_FOUND", "message": "not found"}})

    do_GET = handle_request
    do_POST = handle_request
    do_PATCH = handle_request


class GpuSchedulerClientTest(unittest.TestCase):
    def setUp(self):
        FakeHandler.requests = []
        FakeHandler.job_status = "QUEUED"
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), FakeHandler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.client = GpuSchedulerClient(
            f"http://127.0.0.1:{self.server.server_port}", "test-key"
        )

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)

    def test_environment_configuration_is_required(self):
        with patch.dict(os.environ, {}, clear=True):
            with self.assertRaises(RuntimeError):
                GpuSchedulerClient.from_environment()

    def test_environment_can_read_api_key_from_secret_file(self):
        with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8") as secret:
            secret.write("test-key\n")
            secret.flush()
            with patch.dict(os.environ, {
                "AID_GPU_MANAGER_URL": f"http://127.0.0.1:{self.server.server_port}",
                "AID_GPU_MANAGER_API_KEY_FILE": secret.name,
            }, clear=True):
                client = GpuSchedulerClient.from_environment()
                self.assertIn("h3-single-fast", client.profiles())

    def test_submit_uses_stable_idempotency_key_and_hides_token_from_payload(self):
        job = self.client.submit(
            "h3-single-fast", {"projectId": 26}, "project-26-shot-3-v1"
        )
        self.assertEqual(7, job["id"])
        _, _, headers, body = FakeHandler.requests[-1]
        self.assertEqual("project-26-shot-3-v1", body["idempotencyKey"])
        self.assertNotIn("test-key", json.dumps(body))
        self.assertEqual("Bearer test-key", headers["Authorization"])

    def test_submit_requires_idempotency_key(self):
        with self.assertRaises(ValueError):
            self.client.submit("h3-single-fast", {}, "")

    def test_api_errors_keep_machine_readable_code(self):
        with self.assertRaises(SchedulerApiError) as raised:
            self.client.reprioritize(7, 999)
        self.assertEqual(403, raised.exception.status)
        self.assertEqual("PRIORITY_NOT_ALLOWED", raised.exception.code)

    def test_wait_returns_terminal_job_without_resubmitting(self):
        FakeHandler.job_status = "SUCCEEDED"
        job = self.client.wait(7, timeout_seconds=1, poll_interval_seconds=1)
        self.assertEqual("SUCCEEDED", job["status"])
        self.assertEqual(1, len(FakeHandler.requests))


if __name__ == "__main__":
    unittest.main()
