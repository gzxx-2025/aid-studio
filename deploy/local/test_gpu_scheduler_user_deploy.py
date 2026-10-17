import tempfile
import unittest
from pathlib import Path

from gpu_scheduler_user_deploy import user_unit


class UserDeployTest(unittest.TestCase):
    def test_agent_unit_is_observe_user_service_without_privileged_user_directive(self):
        text = user_unit("agent", Path("/home/neo/app"), Path("/home/neo/config"), Path("/home/neo/state"))
        self.assertIn("gpu_resource_agent.py", text)
        self.assertNotIn("User=", text)
        self.assertIn("NoNewPrivileges=true", text)

    def test_manager_binds_loopback(self):
        text = user_unit("manager", Path("/home/neo/app"), Path("/home/neo/config"), Path("/home/neo/state"))
        self.assertIn("--host 127.0.0.1", text)
        self.assertIn("--services", text)


if __name__ == "__main__":
    unittest.main()
