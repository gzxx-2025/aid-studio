import importlib.util
import unittest
from argparse import Namespace
from pathlib import Path


MODULE_PATH = Path(__file__).with_name("run_conditioning_cache_benchmark.py")
SPEC = importlib.util.spec_from_file_location("h3_conditioning_cache_benchmark", MODULE_PATH)
benchmark = importlib.util.module_from_spec(SPEC)
assert SPEC and SPEC.loader
SPEC.loader.exec_module(benchmark)


class ConditioningCacheGraphTest(unittest.TestCase):
    def args(self, **changes):
        values = {
            "mode": "dual-dense", "dit": "int8", "text_encoder": "int8",
            "exchange_chunks": 8, "prompt": "fixed prompt", "width": 832,
            "height": 480, "duration": 4.0, "steps": 20,
        }
        values.update(changes)
        return Namespace(**values)

    def test_encode_writes_conditioning_only(self):
        graph = benchmark.encode_graph(self.args(), "case")
        self.assertEqual("H3SaveConditioning", graph["4"]["class_type"])
        self.assertEqual(["3", 0], graph["4"]["inputs"]["conditioning"])

    def test_sample_loads_conditioning_and_empty_latent(self):
        graph = benchmark.sample_graph(self.args(), "case", 42)
        self.assertEqual("case.cond.pt", graph["6"]["inputs"]["file_name"])
        self.assertEqual("EmptyMiniMaxH3LatentAV", graph["7"]["class_type"])
        self.assertEqual(42, graph["9"]["inputs"]["noise_seed"])
        self.assertEqual("H3SaveLatentAV", graph["13"]["class_type"])

    def test_quantized_model_names_are_explicit(self):
        graph = benchmark.encode_graph(self.args(text_encoder="nvfp4"), "case")
        self.assertIn("nvfp4", graph["1"]["inputs"]["clip_name"])
        graph, _ = benchmark.model_graph(self.args(dit="w6a8"))
        self.assertIn("w6a8", graph["1"]["inputs"]["unet_name"])


if __name__ == "__main__":
    unittest.main()
