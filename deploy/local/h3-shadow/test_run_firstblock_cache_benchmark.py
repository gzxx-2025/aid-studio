import importlib.util
import unittest
from argparse import Namespace
from pathlib import Path


MODULE_PATH = Path(__file__).with_name("run_firstblock_cache_benchmark.py")
SPEC = importlib.util.spec_from_file_location("h3_firstblock_cache_benchmark", MODULE_PATH)
benchmark = importlib.util.module_from_spec(SPEC)
assert SPEC and SPEC.loader
SPEC.loader.exec_module(benchmark)


class FirstBlockCacheGraphTest(unittest.TestCase):
    def args(self):
        return Namespace(width=832, height=480, duration=4.0)

    def test_baseline_passes_model_through_cache_node(self):
        graph, model = benchmark.model_graph("baseline")
        self.assertEqual("Off", graph["4"]["inputs"]["mode"])
        self.assertEqual(["5", 0], model)

    def test_safe_uses_calibrated_preset(self):
        graph, _ = benchmark.model_graph("safe")
        self.assertEqual("H3 Safe — 0.08 / max 2", graph["4"]["inputs"]["mode"])

    def test_sample_uses_cached_reference_latent_and_twenty_steps(self):
        graph = benchmark.sample_graph(self.args(), "refs", 42, "fast", "sample-fast")
        self.assertEqual("refs.cond.pt", graph["6"]["inputs"]["file_name"])
        self.assertEqual("refs-input.lat.pt", graph["7"]["inputs"]["file_name"])
        self.assertEqual("res_multistep", graph["10"]["inputs"]["sampler_name"])
        self.assertEqual(20, graph["11"]["inputs"]["steps"])
        self.assertEqual("sample-fast", graph["13"]["inputs"]["filename"])


if __name__ == "__main__":
    unittest.main()
