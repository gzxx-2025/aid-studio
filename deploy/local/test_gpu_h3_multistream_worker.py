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

    def test_conditioning_cache_quantization(self):
        result = worker.validated(self.job(
            experiment="conditioning-cache", mode="dual-dense", model="standard",
            resolution="480P", duration=4, steps=20, exchangeChunks=8,
            dit="w6a8", textEncoder="nvfp4",
            skipBaseline=True,
        ))
        self.assertEqual("conditioning-cache", result["experiment"])
        self.assertEqual("w6a8", result["dit"])
        self.assertEqual("nvfp4", result["textEncoder"])
        self.assertTrue(result["skipBaseline"])

    def test_conditioning_cache_rejects_sparse_mode(self):
        with self.assertRaisesRegex(RuntimeError, "requires the standard dense model"):
            worker.validated(self.job(
                experiment="conditioning-cache", mode="dual-sol", model="standard",
                resolution="480P", duration=4, steps=20,
            ))

    def test_collects_phase_cache_media_outputs(self):
        result = worker.result_refs({
            "stages": [
                {"stage": "encode", "output": None},
                {"stage": "decode-first", "output": {"filename": "first.mp4"}},
                {"stage": "decode-repeat", "output": {"filename": "repeat.mp4"}},
            ],
        })
        self.assertEqual(
            [{"filename": "first.mp4"}, {"filename": "repeat.mp4"}], result,
        )

    def test_reference_cache_requires_ordered_image_basenames(self):
        result = worker.validated(self.job(
            experiment="reference-cache", mode="dual-dense", model="standard",
            resolution="480P", duration=4, steps=4, exchangeChunks=8,
            referenceImages=["person-a.png", "person-b.png"],
        ))
        self.assertEqual(["person-a.png", "person-b.png"], result["referenceImages"])

    def test_reference_cache_rejects_paths(self):
        with self.assertRaisesRegex(RuntimeError, "basenames"):
            worker.validated(self.job(
                experiment="reference-cache", mode="dual-dense", model="standard",
                resolution="480P", duration=4, steps=4,
                referenceImages=["../person-a.png", "person-b.png"],
            ))

    def test_firstblock_cache_requires_twenty_steps(self):
        result = worker.validated(self.job(
            experiment="firstblock-cache", mode="single-dense", model="standard",
            resolution="480P", duration=4, steps=20, exchangeChunks=8,
            referenceImages=["person-a.png", "person-b.png"],
        ))
        self.assertEqual("firstblock-cache", result["experiment"])
        with self.assertRaisesRegex(RuntimeError, "requires 480P and 20 steps"):
            worker.validated(self.job(
                experiment="firstblock-cache", mode="single-dense", model="standard",
                resolution="480P", duration=4, steps=4,
                referenceImages=["person-a.png", "person-b.png"],
            ))

    def test_pdd_sage_accepts_isolated_768_variants(self):
        pdd = worker.validated(self.job(
            experiment="pdd-sage", mode="dual-dense", model="standard",
            resolution="768P", duration=5, steps=8,
            acceleration="pdd", attention="kitchen",
        ))
        self.assertEqual("pdd", pdd["acceleration"])
        standard = worker.validated(self.job(
            experiment="pdd-sage", mode="dual-dense", model="standard",
            resolution="768P", duration=5, steps=20,
            acceleration="standard", attention="sage",
        ))
        self.assertEqual("sage", standard["attention"])

    def test_pdd_sage_rejects_wrong_step_count(self):
        with self.assertRaisesRegex(RuntimeError, "steps do not match"):
            worker.validated(self.job(
                experiment="pdd-sage", mode="dual-dense", model="standard",
                resolution="768P", duration=5, steps=20,
                acceleration="pdd", attention="kitchen",
            ))


if __name__ == "__main__":
    unittest.main()
