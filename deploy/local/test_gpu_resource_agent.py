import sys
import tempfile
import threading
import unittest
from pathlib import Path
from unittest.mock import patch

from gpu_resource_agent import NodeAgent, _proc_details, run_fixed, worker_result


class AgentCommandSafetyTest(unittest.TestCase):
    def test_only_job_file_placeholder_is_substituted(self):
        path = Path("/tmp/job-1.json")
        command = NodeAgent._materialize(
            ["/usr/local/libexec/aid-gpu/run-job", "{job_file}", "literal;$HOME"],
            path,
        )
        self.assertEqual(
            ["/usr/local/libexec/aid-gpu/run-job", str(path), "literal;$HOME"],
            command,
        )

    def test_command_arguments_are_not_evaluated_by_a_shell(self):
        with tempfile.TemporaryDirectory() as directory:
            marker = Path(directory) / "must-not-exist"
            malicious = f"$(touch {marker})"
            result = run_fixed([
                sys.executable,
                "-c",
                "import sys; print(sys.argv[1])",
                malicious,
            ])
            self.assertEqual(0, result.returncode)
            self.assertEqual(malicious, result.stdout.strip())
            self.assertFalse(marker.exists())

    def test_stale_termination_request_is_safe_and_idempotent(self):
        agent = NodeAgent.__new__(NodeAgent)
        agent.config = {"terminationGraceSeconds": 1}
        agent.execution_mode = "enforce"
        with patch("gpu_resource_agent.discover_gpu_processes", return_value=[]), \
                patch("gpu_resource_agent.os.kill") as kill:
            success, result = agent._terminate_unauthorized({
                "pid": 99999,
                "processStartId": "old-start",
            })
        self.assertTrue(success)
        self.assertIn("无需发送信号", result)
        kill.assert_not_called()

    def test_observe_mode_never_sends_a_signal(self):
        agent = NodeAgent.__new__(NodeAgent)
        agent.config = {"terminationGraceSeconds": 1}
        agent.execution_mode = "observe"
        with patch("gpu_resource_agent.os.kill") as kill:
            success, result = agent._terminate_unauthorized({"pid": 10, "processStartId": "1"})
        self.assertFalse(success)
        self.assertIn("observe", result)
        kill.assert_not_called()

    def test_windows_process_details_use_stable_create_time(self):
        fake_process = unittest.mock.Mock()
        fake_process.create_time.return_value = 123.4567894
        fake_process.username.return_value = "AISTATION\\neo"
        fake_process.cmdline.return_value = ["C:\\Python313\\python.exe", "service.py"]
        with patch("gpu_resource_agent.os.name", "nt"), \
                patch("gpu_resource_agent.psutil") as psutil:
            psutil.Process.return_value = fake_process
            details = _proc_details(321)
        self.assertEqual("123.456789", details["processStartId"])
        self.assertEqual(321, details["processGroupId"])
        self.assertIn("service.py", details["commandLine"])
        self.assertEqual("", details["cgroup"])

    def test_agent_token_can_be_loaded_from_file(self):
        with tempfile.TemporaryDirectory() as directory:
            token_file = Path(directory) / "agent.token"
            token_file.write_text("secret-token\n", encoding="utf-8")
            with patch("gpu_resource_agent.discover_gpus", return_value=[]):
                agent = NodeAgent({
                    "managerUrl": "http://127.0.0.1:18120",
                    "agentTokenFile": str(token_file),
                    "spoolDir": str(Path(directory) / "spool"),
                    "executionMode": "observe",
                })
        self.assertEqual("secret-token", agent.token)

    def test_worker_result_uses_final_json_line(self):
        value = worker_result('loading model\n{"videoUrl":"https://media.example/out.mp4"}\n', required=True)
        self.assertEqual("https://media.example/out.mp4", value["videoUrl"])

    def test_required_worker_result_rejects_plain_text(self):
        with self.assertRaisesRegex(RuntimeError, "not result JSON"):
            worker_result("done", required=True)

    def test_low_vram_idle_daemon_is_annotated_as_infrastructure(self):
        agent = NodeAgent.__new__(NodeAgent)
        agent._active_lock = threading.Lock()
        agent.authorized_jobs = {}
        agent.active_profiles = set()
        agent.profiles = {}
        agent.idle_infrastructure = [{
            "profile": "h3-single-fast",
            "maxIdleVramMb": 512,
            "allowedProcessPatterns": [r"comfyui-fasth3-shadow.*--port 8191"],
        }]
        result = agent._annotate_authorization([{
            "gpuId": "GPU-0", "pid": 10, "processGroupId": 10,
            "processName": "python", "usedVramMb": 256,
            "commandLine": "comfyui-fasth3-shadow main.py --port 8191", "cgroup": "",
        }])
        self.assertTrue(result[0]["matchedInfrastructure"])
        self.assertEqual(512, result[0]["infrastructureMaxIdleVramMb"])


if __name__ == "__main__":
    unittest.main()
