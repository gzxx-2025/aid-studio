import unittest
from unittest.mock import patch

import gpu_fasth3_worker as worker


def job(content=None, duration=5, resolution="480P"):
    return {
        "profile": "h3-single-fast",
        "payload": {"videoRequest": {
            "duration": duration,
            "resolution": resolution,
            "ratio": "16:9",
            "content": content or [{"type": "text", "text": "A quiet rural sunrise."}],
        }},
    }


class FastH3WorkerTest(unittest.TestCase):
    def test_preview_contract_forces_fast_profile(self):
        request = worker.validated_request(job())
        self.assertEqual("fast_vsa", request["acceleration"])
        self.assertEqual("480P", request["resolution"])

    def test_preview_rejects_reference_media(self):
        with self.assertRaisesRegex(RuntimeError, "text-to-video only"):
            worker.validated_request(job(content=[
                {"type": "text", "text": "Animate it."},
                {"type": "image_url", "image_url": {"url": "https://example.test/ref.png"}},
            ]))

    def test_preview_rejects_long_or_high_resolution_requests(self):
        with self.assertRaisesRegex(RuntimeError, "4 to 5 seconds"):
            worker.validated_request(job(duration=3))
        with self.assertRaisesRegex(RuntimeError, "4 to 5 seconds"):
            worker.validated_request(job(duration=6))
        with self.assertRaisesRegex(RuntimeError, "only 480P"):
            worker.validated_request(job(resolution="768P"))

    def test_result_url_replaces_private_loopback_base(self):
        with patch.dict(worker.os.environ, {
            "H3_FAST_RESULT_BASE": "http://10.190.0.222:8191",
        }):
            self.assertEqual(
                "http://10.190.0.222:8191/view?filename=result.mp4",
                worker.public_result_url(
                    "http://127.0.0.1:8191/view?filename=result.mp4",
                    "http://127.0.0.1:8191",
                ),
            )

    @patch("gpu_fasth3_worker.h3.build_workflow")
    @patch("gpu_fasth3_worker.wait_runtime_nodes")
    def test_validate_only_compiles_without_submitting(self, wait_runtime, build_workflow):
        wait_runtime.return_value = set(worker.REQUIRED_FAST_NODES)
        build_workflow.return_value = ({"1": {}}, {
            "resolution": "480P", "width": 832, "height": 480,
            "duration": 5, "acceleration": "fast_vsa",
        })

        result = worker.validate_runtime(worker.validated_request(job()))

        self.assertTrue(result["ok"])
        self.assertEqual("validate-only", result["mode"])
        wait_runtime.assert_called_once()

    @patch("gpu_fasth3_worker.wait_runtime_nodes", return_value=set())
    def test_execute_waits_for_required_runtime_nodes(self, wait_runtime):
        with self.assertRaisesRegex(RuntimeError, "runtime is missing nodes"):
            worker.execute(worker.validated_request(job()), 60)

        wait_runtime.assert_called_once()


if __name__ == "__main__":
    unittest.main()
