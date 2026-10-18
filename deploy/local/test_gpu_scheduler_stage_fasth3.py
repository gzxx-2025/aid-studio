import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from gpu_scheduler_stage_fasth3 import stage_fasth3


class FastH3StageTest(unittest.TestCase):
    @patch("gpu_scheduler_stage_fasth3.validate_preflight")
    @patch("gpu_scheduler_stage_fasth3.run", return_value='{"ok":true}')
    def test_stage_keeps_observe_mode_and_installs_fixed_command(self, run, validate):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            home = root / "home"
            source = root / "source"
            source.mkdir()
            config = home / ".config/aid-gpu-scheduler"
            config.mkdir(parents=True)
            state = home / ".local/state/aid-gpu-scheduler"
            state.mkdir(parents=True)
            agent_path = config / "agent.json"
            agent_path.write_text(json.dumps({"executionMode": "observe", "profiles": {}}))
            for name in (
                "gpu_fasth3_worker.py", "comfy_h3_adapter.py", "h3_i2v_api.json",
                "fasth3-validate-job.json",
            ):
                (source / name).write_text("{}")
            preflight = root / "preflight.json"
            preflight.write_text("{}")

            manifest = stage_fasth3(source, preflight, "approved", home, "/python")

            agent = json.loads(agent_path.read_text())
            self.assertEqual("observe", agent["executionMode"])
            profile = agent["profiles"]["h3-single-fast"]
            self.assertTrue(profile["resultRequired"])
            self.assertEqual("{job_file}", profile["runCommand"][-1])
            self.assertEqual(
                ["/usr/bin/systemctl", "--user", "start", "comfyui-fasth3.service"],
                profile["startCommand"],
            )
            self.assertEqual(
                ["/usr/bin/systemctl", "--user", "stop", "comfyui-fasth3.service"],
                profile["stopCommand"],
            )
            self.assertEqual(4, len(agent["idleInfrastructure"]))
            standard_idle = next(
                item for item in agent["idleInfrastructure"]
                if item.get("profile") == "h3-standard-quality"
            )
            self.assertEqual(2048, standard_idle["maxIdleVramMb"])
            self.assertFalse(any(
                item.get("profile") == "qwen38-local-llm"
                for item in agent["idleInfrastructure"]
            ))
            self.assertTrue(manifest.is_file())
            self.assertEqual(
                ["/usr/bin/systemctl", "--user", "restart", "gpu-resource-agent.service"],
                run.call_args_list[-1].args[0],
            )
            validate.assert_called_once_with(preflight, "approved")


if __name__ == "__main__":
    unittest.main()
