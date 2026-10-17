import unittest

import gpu_h3_multistream_worker as worker


class H3MultiStreamWorkerTest(unittest.TestCase):
    def job(self, **values):
        return {"profile": "h3-dual-dense", "payload": {"benchmark": values}}

    def test_dense_768_profile(self):
        result = worker.validated(self.job(
            mode="dual-dense", model="standard", resolution="768P",
            duration=5, steps=20, exchangeChunks=8,
        ))
        self.assertEqual("dual-dense", result["mode"])
        self.assertEqual("768P", result["resolution"])

    def test_vsa_requires_fast_checkpoint(self):
        with self.assertRaisesRegex(RuntimeError, "requires model=fast"):
            worker.validated(self.job(
                mode="dual-vsa", model="standard", resolution="480P",
                duration=4, steps=8, exchangeChunks=8,
            ))

    def test_fast_checkpoint_requires_eight_steps(self):
        with self.assertRaisesRegex(RuntimeError, "requires 8 steps"):
            worker.validated(self.job(
                mode="dual-vsa", model="fast", resolution="480P",
                duration=4, steps=4, exchangeChunks=8,
            ))

    def test_rejects_unknown_mode(self):
        with self.assertRaisesRegex(RuntimeError, "unsupported benchmark mode"):
            worker.validated(self.job(mode="shell"))


if __name__ == "__main__":
    unittest.main()
