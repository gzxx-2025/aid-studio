import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from gpu_h3_veda_worker import load_job, public_result_url, validated_request


def job(**overrides):
    request = {
        "model": "MiniMax-H3", "duration": 5, "resolution": "480P",
        "acceleration": "fast_veda",
        "content": [{"type": "text", "text": "A woman turns toward the window."}],
    }
    request.update(overrides)
    return {"profile": "h3-veda-fast", "payload": {"videoRequest": request}}


class H3VedaWorkerTest(unittest.TestCase):
    def test_accepts_low_resolution_text_request(self):
        request = validated_request(job())
        self.assertEqual("fast_veda", request["acceleration"])
        self.assertEqual("480P", request["resolution"])

    def test_rejects_reference_media(self):
        with self.assertRaisesRegex(RuntimeError, "text-to-video only"):
            validated_request(job(content=[
                {"type": "text", "text": "prompt"},
                {"type": "image_url", "image_url": {"url": "https://example.test/ref.png"}},
            ]))

    def test_rejects_unvalidated_resolution(self):
        with self.assertRaisesRegex(RuntimeError, "only 480P"):
            validated_request(job(resolution="768P"))

    def test_rejects_wrong_profile(self):
        value = job()
        value["profile"] = "h3-single-fast"
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "job.json"
            path.write_text(json.dumps(value))
            with self.assertRaisesRegex(RuntimeError, "h3-veda-fast"):
                load_job(path)

    @patch.dict("os.environ", {"H3_VEDA_RESULT_BASE": "http://10.190.0.222:8192"})
    def test_rewrites_private_result_url(self):
        self.assertEqual(
            "http://10.190.0.222:8192/view/out.mp4",
            public_result_url(
                "http://127.0.0.1:8192/view/out.mp4", "http://127.0.0.1:8192"
            ),
        )


if __name__ == "__main__":
    unittest.main()
