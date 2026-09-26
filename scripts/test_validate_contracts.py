"""Regression check for the repository's contract and case-matrix references."""

import subprocess
import sys
import unittest
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parent.parent


class ValidateContractsTest(unittest.TestCase):
    def test_repository_feature_tags_have_matrix_cases(self):
        result = subprocess.run(
            [sys.executable, "scripts/validate-contracts.py"],
            cwd=REPO_ROOT,
            capture_output=True,
            text=True,
            check=False,
        )
        self.assertEqual(0, result.returncode, result.stderr)


if __name__ == "__main__":
    unittest.main()
