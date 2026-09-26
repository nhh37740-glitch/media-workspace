"""Regression checks for repository-specific change-scope ownership."""

import unittest

from check_change_scope import check_paths, owner


class ChangeScopeTest(unittest.TestCase):
    def test_build_delivery_can_update_ci_matrix_with_its_gate_and_tests(self):
        paths = [
            "docs/test-matrix.md",
            "scripts/check_change_scope.py",
            "scripts/test_change_scope.py",
            "scripts/test_validate_contracts.py",
        ]
        self.assertEqual("build-delivery", owner("docs/test-matrix.md"))
        self.assertEqual([], check_paths(paths, "build-delivery"))

    def test_matrix_ownership_does_not_exempt_other_modules_or_docs(self):
        self.assertIn(
            "Outside module web: docs/test-matrix.md",
            check_paths(["web/src/App.vue", "docs/test-matrix.md"], "web"),
        )
        self.assertIn(
            "Outside module build-delivery: docs/architecture.md",
            check_paths(["scripts/check_change_scope.py", "docs/architecture.md"],
                        "build-delivery"),
        )


if __name__ == "__main__":
    unittest.main()
