import unittest
from unittest.mock import patch

from gpu_h3_quality_runtime import health, queue_idle, release


class H3QualityRuntimeTest(unittest.TestCase):
    @patch("gpu_h3_quality_runtime.request_json")
    def test_health_requires_idle_queue(self, request):
        request.side_effect = [
            {"queue_running": [], "queue_pending": []},
            {"system": {"comfyui_version": "0.39.0"}},
        ]
        self.assertEqual("0.39.0", health()["comfyVersion"])

    @patch("gpu_h3_quality_runtime.request_json")
    def test_busy_queue_blocks_release(self, request):
        request.return_value = {"queue_running": [[1]], "queue_pending": []}
        with self.assertRaisesRegex(RuntimeError, "busy"):
            release()

    @patch("gpu_h3_quality_runtime.request_json")
    def test_release_unloads_only_after_idle_check(self, request):
        request.side_effect = [{"queue_running": [], "queue_pending": []}, {}]
        result = release()
        self.assertTrue(result["ok"])
        request.assert_called_with("/free", body={"unload_models": True, "free_memory": True})

    @patch("gpu_h3_quality_runtime.request_json", return_value={
        "queue_running": [], "queue_pending": []
    })
    def test_queue_idle(self, _request):
        self.assertEqual({"running": 0, "pending": 0}, queue_idle())


if __name__ == "__main__":
    unittest.main()
