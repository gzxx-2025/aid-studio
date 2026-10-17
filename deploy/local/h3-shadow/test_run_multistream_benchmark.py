import importlib.util
import unittest
from pathlib import Path


MODULE_PATH = Path(__file__).with_name("run_multistream_benchmark.py")
SPEC = importlib.util.spec_from_file_location("h3_multistream_benchmark", MODULE_PATH)
benchmark = importlib.util.module_from_spec(SPEC)
assert SPEC and SPEC.loader
SPEC.loader.exec_module(benchmark)


class UniqueNodeIdsTest(unittest.TestCase):
    def test_changes_ids_and_preserves_links(self):
        graph = {
            "1": {"class_type": "Source", "inputs": {"seed": 7}},
            "2": {"class_type": "Sink", "inputs": {"source": ["1", 0]}},
        }

        first = benchmark.unique_node_ids(graph)
        second = benchmark.unique_node_ids(graph)

        self.assertNotEqual(set(first), set(second))
        sink_id = next(key for key, node in first.items() if node["class_type"] == "Sink")
        source_id = next(key for key, node in first.items() if node["class_type"] == "Source")
        self.assertEqual([source_id, 0], first[sink_id]["inputs"]["source"])
        self.assertEqual(7, first[source_id]["inputs"]["seed"])


if __name__ == "__main__":
    unittest.main()
