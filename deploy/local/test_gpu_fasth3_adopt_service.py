import unittest

from gpu_fasth3_adopt_service import expected_fasth3_argv


class FastH3AdoptionTest(unittest.TestCase):
    def test_expected_process_signature_is_narrow(self):
        self.assertFalse(expected_fasth3_argv(["/usr/bin/python", "other.py"]))
        self.assertTrue(expected_fasth3_argv([
            "/home/neo/comfyui-fasth3-shadow/env-cu130/bin/python",
            "main.py",
            "--port", "8191",
        ]))


if __name__ == "__main__":
    unittest.main()
