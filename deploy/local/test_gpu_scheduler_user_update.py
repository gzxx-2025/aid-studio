import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from gpu_scheduler_user_update import update_user


class UserControlPlaneUpdateTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        root = Path(self.temp.name)
        self.home = root / "home"
        self.source = root / "source"
        self.source.mkdir(parents=True)
        self.install = self.home / ".local/share/aid-gpu-scheduler"
        self.config = self.home / ".config/aid-gpu-scheduler"
        self.install.mkdir(parents=True)
        self.config.mkdir(parents=True)
        (self.install / "gpu_resource_manager.py").write_text("old-manager")
        (self.install / "gpu_resource_agent.py").write_text("old-agent")
        (self.config / "profiles.json").write_text("old-profiles")
        (self.config / "services.json").write_text("old-services")
        (self.source / "gpu_resource_manager.py").write_text("new-manager")
        (self.source / "gpu_resource_agent.py").write_text("new-agent")
        (self.source / "gpu-resource-profiles.gpu-222.json").write_text("new-profiles")
        (self.source / "gpu-resource-services.gpu-222.json").write_text("new-services")
        self.preflight = root / "preflight.json"
        self.preflight.write_text("{}")

    def tearDown(self):
        self.temp.cleanup()

    @patch("gpu_scheduler_user_update.health_ok", return_value=True)
    @patch("gpu_scheduler_user_update.run")
    @patch("gpu_scheduler_user_update.validate_preflight")
    def test_update_preserves_tokens_and_restarts_only_manager(self, validate, run, _health):
        manifest = update_user(self.source, self.preflight, "approved", self.home)

        validate.assert_called_once_with(self.preflight, "approved")
        self.assertEqual([
            ["/usr/bin/systemctl", "--user", "restart", "gpu-resource-manager.service"],
            ["/usr/bin/systemctl", "--user", "restart", "gpu-resource-agent.service"],
        ], [call.args[0] for call in run.call_args_list])
        self.assertEqual("new-manager", (self.install / "gpu_resource_manager.py").read_text())
        self.assertEqual("new-agent", (self.install / "gpu_resource_agent.py").read_text())
        self.assertEqual("new-profiles", (self.config / "profiles.json").read_text())
        self.assertEqual("new-services", (self.config / "services.json").read_text())
        self.assertTrue(manifest.is_file())


if __name__ == "__main__":
    unittest.main()
