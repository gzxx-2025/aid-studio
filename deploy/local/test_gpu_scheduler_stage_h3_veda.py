import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from gpu_scheduler_stage_h3_veda import ADAPTER_FILES, stage


class H3VedaStageTest(unittest.TestCase):
    @patch("gpu_scheduler_stage_h3_veda.validate_preflight")
    @patch("gpu_scheduler_stage_h3_veda.run", return_value='{"ok":true}')
    def test_stages_fixed_worker_and_lifecycle(self, run, _validate):
        with tempfile.TemporaryDirectory() as source_dir, tempfile.TemporaryDirectory() as home_dir:
            source, home = Path(source_dir), Path(home_dir)
            config = home / ".config/aid-gpu-scheduler"
            config.mkdir(parents=True)
            (config / "agent.json").write_text(json.dumps({"profiles": {}}))
            for name in (*ADAPTER_FILES, "h3-veda-validate-job.json"):
                (source / name).write_text("{}")
            preflight = source / "preflight.json"
            preflight.write_text("{}")
            manifest = stage(source, preflight, "approved", home, "/python")
            agent = json.loads((config / "agent.json").read_text())
            profile = agent["profiles"]["h3-veda-fast"]
            self.assertEqual(
                ["/usr/bin/systemctl", "--user", "start", "comfyui-veda.service"],
                profile["startCommand"],
            )
            self.assertEqual(
                ["/usr/bin/systemctl", "--user", "stop", "comfyui-veda.service"],
                profile["stopCommand"],
            )
            self.assertTrue(manifest.is_file())
            self.assertIn(
                ["/usr/bin/systemctl", "--user", "restart", "gpu-resource-agent.service"],
                [call.args[0] for call in run.call_args_list],
            )


if __name__ == "__main__":
    unittest.main()
