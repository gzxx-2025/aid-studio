import unittest
import urllib.error

from gpu_deployment_preflight import build_report, comfy_queue, parse_celery_output
from unittest.mock import patch


class DeploymentPreflightTest(unittest.TestCase):
    @patch("gpu_deployment_preflight.service_state", return_value="inactive")
    @patch("gpu_deployment_preflight.urllib.request.urlopen", side_effect=urllib.error.URLError("refused"))
    def test_stopped_managed_comfy_is_idle_not_unknown(self, _urlopen, _state):
        result = comfy_queue(8191, "comfyui-fasth3.service", True)
        self.assertEqual("STOPPED_IDLE", result["status"])
        self.assertEqual(0, result["running"])

    def test_empty_celery_queue_is_safe(self):
        result = parse_celery_output("worker", "-> celery@node: OK\n    - empty -\n", 0)
        self.assertEqual("EMPTY", result["status"])
        self.assertEqual(0, result["active"])

    def test_non_empty_celery_response_is_active(self):
        result = parse_celery_output("worker", "-> celery@node: OK\n * {'id': 'task-1'}\n", 0)
        self.assertEqual("ACTIVE", result["status"])

    def test_broker_failure_is_unknown_not_empty(self):
        result = parse_celery_output("worker", "kombu.exceptions.OperationalError: connection refused", 1)
        self.assertEqual("UNKNOWN", result["status"])
        self.assertIsNone(result["active"])

    @patch("gpu_deployment_preflight.gpu_processes", return_value=[])
    @patch("gpu_deployment_preflight.gpu_state", return_value={
        "status": "OK", "gpus": [], "peakUtilizationPercent": {0: 0, 1: 0}
    })
    @patch("gpu_deployment_preflight.celery_queue")
    @patch("gpu_deployment_preflight.comfy_queue", return_value={
        "port": 8188, "status": "OK", "running": 0, "pending": 0
    })
    def test_explicitly_excluded_unknown_service_does_not_block(self, _comfy, celery, _gpu, _processes):
        celery.side_effect = lambda service_id, *_: {
            "serviceId": service_id, "status": "UNKNOWN" if service_id == "avcs-gpu-worker" else "EMPTY",
            "active": None if service_id == "avcs-gpu-worker" else 0,
        }
        report = build_report(excluded_services={"avcs-gpu-worker"}, samples=1, interval=0.1)
        self.assertTrue(report["safeToRequestApproval"])
        self.assertEqual(["avcs-gpu-worker"], report["excludedServices"])


if __name__ == "__main__":
    unittest.main()
