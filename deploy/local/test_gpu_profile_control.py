import unittest
from unittest.mock import patch

from gpu_profile_control import ControlError, control, controller_command


class ProfileControlTest(unittest.TestCase):
    def test_system_controller_is_fixed_argument_array(self):
        command = controller_command({"type": "systemd-system", "units": ["comfyui.service"]}, "stop")
        self.assertEqual(["/usr/bin/systemctl", "stop", "comfyui.service"], command)

    def test_invalid_unit_name_is_rejected(self):
        with self.assertRaises(ControlError):
            controller_command({"type": "systemd-system", "units": ["x;shutdown"]}, "stop")

    def test_plan_never_executes_service_change(self):
        config = {"profiles": {"h3": {"controller": {"type": "systemd-system", "units": ["comfyui.service"]}}}}
        with patch("gpu_profile_control.run") as execute:
            result = control(config, "h3", "start", allow_changes=False)
        self.assertFalse(result["changed"])
        execute.assert_not_called()

    def test_unknown_profile_is_rejected(self):
        with self.assertRaises(ControlError):
            control({"profiles": {}}, "arbitrary", "start")


if __name__ == "__main__":
    unittest.main()
