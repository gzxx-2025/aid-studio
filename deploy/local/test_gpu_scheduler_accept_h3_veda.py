import json
import tempfile
import unittest
from pathlib import Path

from gpu_scheduler_accept_h3_veda import atomic_json, client_token, read_env, update_service
from gpu_scheduler_deploy import DeployError


class H3VedaAcceptanceTest(unittest.TestCase):
    def test_atomic_json_creates_new_manifest(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "manifest.json"
            atomic_json(path, {"status": "succeeded"})
            self.assertEqual("succeeded", json.loads(path.read_text())["status"])

    def test_reads_tokens_without_shell_evaluation(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "manager.env"
            path.write_text('GPU_MANAGER_ADMIN_TOKEN="admin-token"\nGPU_MANAGER_CLIENT_KEYS=aid=aid-token,other=x\n')
            values = read_env(path)
        self.assertEqual("admin-token", values["GPU_MANAGER_ADMIN_TOKEN"])
        self.assertEqual("aid-token", client_token(values["GPU_MANAGER_CLIENT_KEYS"]))

    def test_updates_only_veda_service(self):
        services = {"services": [
            {"id": "h3-veda-worker", "adapterReady": False},
            {"id": "h3-standard-comfyui", "adapterReady": True},
        ]}
        update_service(services, adapterReady=True, currentState="VALIDATION_PENDING")
        self.assertTrue(services["services"][0]["adapterReady"])
        self.assertEqual("VALIDATION_PENDING", services["services"][0]["currentState"])
        self.assertNotIn("currentState", services["services"][1])

    def test_missing_service_is_rejected(self):
        with self.assertRaises(DeployError):
            update_service({"services": []}, adapterReady=True)


if __name__ == "__main__":
    unittest.main()
