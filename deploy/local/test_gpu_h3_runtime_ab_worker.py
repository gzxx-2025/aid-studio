import tempfile
import unittest
from unittest import mock
from pathlib import Path

import gpu_h3_runtime_ab_worker as worker


class H3RuntimeAbWorkerTest(unittest.TestCase):
    def job(self, **values):
        return {"profile": "h3-runtime-ab", "payload": {"benchmark": values}}

    def test_accepts_fixed_runtime_comparison(self):
        result = worker.validated(self.job(
            resolution="480P", duration=4, steps=20, warmRuns=1,
            targets=["torch-2.8-cu128"],
        ))
        self.assertEqual("480P", result["resolution"])
        self.assertEqual(1, result["warmRuns"])
        self.assertEqual(["torch-2.8-cu128"], result["targets"])

    def test_rejects_acceleration_step_change(self):
        with self.assertRaisesRegex(RuntimeError, "unsupported benchmark parameters"):
            worker.validated(self.job(
                resolution="480P", duration=4, steps=8, warmRuns=1,
            ))

    def test_rejects_wrong_profile(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "job.json"
            path.write_text('{"profile":"h3-standard-quality"}', encoding="utf-8")
            with self.assertRaisesRegex(RuntimeError, "profile must be h3-runtime-ab"):
                worker.load_job(path)

    def test_accepts_empty_success_response_from_comfy_free(self):
        response = mock.MagicMock()
        response.read.return_value = b""
        response.__enter__.return_value = response
        with mock.patch.object(worker.urllib.request, "urlopen", return_value=response):
            self.assertEqual({}, worker.request_json("http://127.0.0.1:8188/free", {}))

    def test_rejects_unknown_runtime_target(self):
        with self.assertRaisesRegex(RuntimeError, "runtime targets"):
            worker.validated(self.job(
                resolution="480P", duration=4, steps=20, warmRuns=1,
                targets=["aki-bundle"],
            ))


if __name__ == "__main__":
    unittest.main()
