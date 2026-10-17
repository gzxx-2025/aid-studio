import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from gpu_h3_quality_worker import execute, load_job, public_result_url, validated_request


def job(**overrides):
    request = {
        "model": "MiniMax-H3",
        "duration": 5,
        "resolution": "768P",
        "acceleration": "turbo",
        "content": [
            {"type": "text", "text": "A woman turns toward the window."},
            {"type": "image_url", "image_url": {"url": "https://example.test/ref.png"},
             "role": "reference_image"},
        ],
    }
    request.update(overrides)
    return {"profile": "h3-standard-quality", "payload": {"videoRequest": request}}


class H3QualityWorkerTest(unittest.TestCase):
    def test_accepts_turbo_reference_request(self):
        request = validated_request(job())
        self.assertEqual("turbo", request["acceleration"])
        self.assertEqual("768P", request["resolution"])
        self.assertEqual(2, len(request["content"]))

    def test_rejects_unapproved_acceleration(self):
        with self.assertRaisesRegex(RuntimeError, "stable or turbo"):
            validated_request(job(acceleration="fast_vsa"))

    def test_rejects_unsafe_768p_duration(self):
        with self.assertRaisesRegex(RuntimeError, "at most 5 seconds"):
            validated_request(job(duration=6))

    def test_rejects_wrong_profile(self):
        value = job()
        value["profile"] = "h3-single-fast"
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "job.json"
            path.write_text(json.dumps(value))
            with self.assertRaisesRegex(RuntimeError, "h3-standard-quality"):
                load_job(path)

    @patch.dict("os.environ", {"H3_QUALITY_RESULT_BASE": "http://10.190.0.222:8188"})
    def test_rewrites_private_result_url(self):
        self.assertEqual(
            "http://10.190.0.222:8188/view/out.mp4",
            public_result_url(
                "http://127.0.0.1:8188/view/out.mp4", "http://127.0.0.1:8188"
            ),
        )

    @patch("gpu_h3_quality_worker.wait_runtime_nodes", return_value=set())
    def test_execute_waits_for_required_runtime_nodes(self, wait_runtime):
        with self.assertRaisesRegex(RuntimeError, "runtime is missing nodes"):
            execute(validated_request(job()), 60)

        wait_runtime.assert_called_once()


if __name__ == "__main__":
    unittest.main()
