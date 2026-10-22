import importlib.util
import unittest
from argparse import Namespace
from pathlib import Path


MODULE_PATH = Path(__file__).with_name("run_reference_cache_benchmark.py")
SPEC = importlib.util.spec_from_file_location("h3_reference_cache_benchmark", MODULE_PATH)
benchmark = importlib.util.module_from_spec(SPEC)
assert SPEC and SPEC.loader
SPEC.loader.exec_module(benchmark)


class ReferenceCacheGraphTest(unittest.TestCase):
    def args(self, **changes):
        values = {
            "mode": "dual-dense", "reference_images": ["person-a.png", "person-b.png"],
            "exchange_chunks": 8, "prompt": "<Picture 1> meets <Picture 2>",
            "width": 832, "height": 480, "duration": 4.0,
        }
        values.update(changes)
        return Namespace(**values)

    def test_encode_saves_reference_conditioning_and_input_latent(self):
        graph = benchmark.encode_graph(self.args(), "case")
        self.assertEqual("MiniMaxH3ReferenceToVideo", graph["4"]["class_type"])
        self.assertEqual(["20", 0], graph["4"]["inputs"]["ref_images.ref_image_0"])
        self.assertEqual(["21", 0], graph["4"]["inputs"]["ref_images.ref_image_1"])
        self.assertEqual(["4", 0], graph["5"]["inputs"]["conditioning"])
        self.assertEqual(["4", 1], graph["6"]["inputs"]["samples"])

    def test_sample_loads_cached_reference_latent(self):
        graph = benchmark.sample_graph(self.args(), "case", 42)
        self.assertEqual("case.cond.pt", graph["6"]["inputs"]["file_name"])
        self.assertEqual("case-input.lat.pt", graph["7"]["inputs"]["file_name"])
        self.assertEqual(["7", 0], graph["12"]["inputs"]["latent_image"])
        self.assertEqual("euler", graph["10"]["inputs"]["sampler_name"])

    def test_full_baseline_keeps_ordered_reference_ports(self):
        graph = benchmark.full_graph(self.args(), 42, "out")
        self.assertEqual(["20", 0], graph["9"]["inputs"]["ref_images.ref_image_0"])
        self.assertEqual(["21", 0], graph["9"]["inputs"]["ref_images.ref_image_1"])
        self.assertEqual(["9", 1], graph["14"]["inputs"]["latent_image"])


if __name__ == "__main__":
    unittest.main()
