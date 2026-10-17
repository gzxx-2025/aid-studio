import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from gpu_scheduler_activate_fasth3 import activate


class FastH3ActivationTest(unittest.TestCase):
    @patch("gpu_scheduler_activate_fasth3.validate_preflight")
    @patch("gpu_scheduler_activate_fasth3.health_ok", return_value=True)
    @patch("gpu_scheduler_activate_fasth3.run")
    def test_activation_requires_acceptance_and_keeps_policy_disabled(self, run, health, preflight):
        with tempfile.TemporaryDirectory() as directory:
            home = Path(directory)
            config = home / ".config/aid-gpu-scheduler"
            state = home / ".local/state/aid-gpu-scheduler"
            config.mkdir(parents=True)
            state.mkdir(parents=True)
            output = home / "accepted.mp4"
            output.write_bytes(b"video")
            acceptance = home / "acceptance.json"
            acceptance.write_text(json.dumps({
                "profile": "h3-single-fast", "status": "succeeded",
                "outputPath": str(output), "resolution": "480P", "duration": 5,
                "elapsedSeconds": 620,
            }))
            (config / "agent.json").write_text(json.dumps({
                "executionMode": "observe",
                "profiles": {"h3-single-fast": {
                    "runCommand": ["worker", "{job_file}"],
                    "startCommand": ["systemctl", "start"],
                    "stopCommand": ["systemctl", "stop"],
                }},
                "idleInfrastructure": [
                    {"profile": "h3-single-fast"},
                    {"profile": "qwen38-local-llm"},
                ],
            }))
            (config / "services.json").write_text(json.dumps({
                "services": [{
                    "id": "h3-fasth3-unmanaged", "profile": "h3-single-fast",
                    "adapterReady": False,
                }],
            }))
            preflight_path = home / "preflight.json"
            preflight_path.write_text("{}")

            manifest = activate(preflight_path, "approved", acceptance, home)

            agent = json.loads((config / "agent.json").read_text())
            services = json.loads((config / "services.json").read_text())
            self.assertEqual("enforce", agent["executionMode"])
            self.assertEqual(["h3-single-fast"], [
                x["profile"] for x in agent["idleInfrastructure"]
            ])
            self.assertTrue(services["services"][0]["adapterReady"])
            self.assertTrue(manifest.is_file())
            self.assertEqual(2, run.call_count)


if __name__ == "__main__":
    unittest.main()
