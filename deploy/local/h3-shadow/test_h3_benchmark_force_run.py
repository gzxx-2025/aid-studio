import math
import unittest

from h3_benchmark_force_run import H3BenchmarkForceRun


class ForceRunTest(unittest.TestCase):
    def test_passthrough_and_always_changed_marker(self):
        model = object()
        self.assertIs(model, H3BenchmarkForceRun().passthrough(model)[0])
        self.assertTrue(math.isnan(H3BenchmarkForceRun.IS_CHANGED()))


if __name__ == "__main__":
    unittest.main()
