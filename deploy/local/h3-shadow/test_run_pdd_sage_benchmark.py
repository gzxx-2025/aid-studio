import argparse
import importlib.util
import sys
import unittest
from pathlib import Path


MODULE_PATH = Path(__file__).with_name("run_pdd_sage_benchmark.py")
sys.path.insert(0, str(MODULE_PATH.parent))
SPEC = importlib.util.spec_from_file_location("h3_pdd_sage_benchmark", MODULE_PATH)
benchmark = importlib.util.module_from_spec(SPEC)
assert SPEC and SPEC.loader
SPEC.loader.exec_module(benchmark)


class PddSageBenchmarkTest(unittest.TestCase):
    def args(self, acceleration="pdd", steps=8):
        return argparse.Namespace(
            acceleration=acceleration, attention="kitchen", width=1344, height=768,
            duration=5.0, steps=steps, seed=42, prefix="test", prompt="prompt",
            timeout_seconds=7200,
        )

    def test_pdd_uses_trained_sigmas_and_euler(self):
        graph = benchmark.build_prompt(self.args(), "comfy kitchen attention")
        self.assertEqual("MiniMaxH3PDDAccApply", graph["13"]["class_type"])
        self.assertEqual(["13", 1], graph["14"]["inputs"]["sigmas"])
        self.assertEqual("euler", graph["12"]["inputs"]["sampler_name"])
        self.assertEqual(["13", 0], graph["5"]["inputs"]["model"])

    def test_standard_keeps_twenty_step_recipe(self):
        graph = benchmark.build_prompt(self.args("standard", 20), "sage attention")
        self.assertEqual("BasicScheduler", graph["13"]["class_type"])
        self.assertEqual("res_multistep", graph["12"]["inputs"]["sampler_name"])
        self.assertEqual(["2", 0], graph["5"]["inputs"]["model"])
        self.assertNotIn("3", graph)


if __name__ == "__main__":
    unittest.main()
