import json
import tempfile
import unittest
from pathlib import Path

from gpu_scheduler_deploy import DeployError, plan, validate_preflight


class SchedulerDeployGuardTest(unittest.TestCase):
    def report(self, directory: str, **overrides):
        body = {
            "schemaVersion": "aid-gpu-deployment-preflight/v1",
            "changesApplied": False,
            "safeToRequestApproval": True,
            "validUntil": 2000,
            "approvalToken": "confirm-me",
        }
        body.update(overrides)
        path = Path(directory) / "preflight.json"
        path.write_text(json.dumps(body), encoding="utf-8")
        return path

    def test_plan_mode_never_applies_changes(self):
        result = plan(Path("/tmp/source"))
        self.assertFalse(result["changesApplied"])
        self.assertEqual([], result["existingGpuServicesStopped"])
        self.assertEqual("observe", result["agentMode"])

    def test_fresh_safe_matching_report_is_accepted(self):
        with tempfile.TemporaryDirectory() as directory:
            report = validate_preflight(self.report(directory), "confirm-me", now=1000)
        self.assertTrue(report["safeToRequestApproval"])

    def test_expired_or_blocked_report_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(DeployError):
                validate_preflight(self.report(directory, validUntil=999), "confirm-me", now=1000)
            with self.assertRaises(DeployError):
                validate_preflight(self.report(directory, safeToRequestApproval=False), "confirm-me", now=1000)

    def test_wrong_approval_token_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(DeployError):
                validate_preflight(self.report(directory), "wrong", now=1000)


if __name__ == "__main__":
    unittest.main()
