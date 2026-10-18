import importlib.util
import unittest
from argparse import Namespace
from pathlib import Path


MODULE_PATH = Path(__file__).with_name("run_runtime_ab_benchmark.py")
SPEC = importlib.util.spec_from_file_location("h3_runtime_ab_benchmark", MODULE_PATH)
benchmark = importlib.util.module_from_spec(SPEC)
assert SPEC and SPEC.loader
SPEC.loader.exec_module(benchmark)


class RuntimeAbBenchmarkTest(unittest.TestCase):
    def args(self):
        return Namespace(
            width=832, height=480, duration=4.0, steps=20, seed=20261017,
            prompt="fixed", prefix="test",
        )

    def test_uses_production_graph_without_shadow_only_nodes(self):
        graph = benchmark.build_prompt(self.args())
        classes = {node["class_type"] for node in graph.values()}
        self.assertNotIn("H3MultiStream", classes)
        self.assertNotIn("H3BenchmarkForceRun", classes)
        self.assertIn("MiniMaxH3ImageToVideo", classes)
        self.assertIn("VAEDecodeTiled", classes)

    def test_frame_count_matches_h3_constraint(self):
        self.assertEqual(107, benchmark.frame_count(4.0))
        self.assertEqual(124, benchmark.frame_count(5.0))

    def test_unique_ids_preserve_graph_links(self):
        graph = benchmark.build_prompt(self.args())
        unique = benchmark.unique_node_ids(graph)
        self.assertNotEqual(set(graph), set(unique))
        decode = next(node for node in unique.values()
                      if node["class_type"] == "VAEDecodeTiled")
        self.assertIn(decode["inputs"]["samples"][0], unique)


if __name__ == "__main__":
    unittest.main()
