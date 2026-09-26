"""Guard calls to shell scripts that a fresh Git checkout leaves non-executable."""

import re
import subprocess
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parent.parent
SCRIPT_REFERENCE = re.compile(r'"\$REPO_ROOT/scripts/([^\"]+\.sh)"')


class DeployScriptModesTest(unittest.TestCase):
    def test_checkout_scripts_are_invoked_through_bash(self):
        for caller in ("deploy-demo.sh", "ci-prepare.sh"):
            source = (ROOT / "scripts" / caller).read_text(encoding="utf-8")
            for number, line in enumerate(source.splitlines(), 1):
                if line.lstrip().startswith("#"):
                    continue
                for match in SCRIPT_REFERENCE.finditer(line):
                    script = "scripts/" + match.group(1)
                    entry = subprocess.check_output(
                        ["git", "ls-files", "-s", "--", script], cwd=ROOT, text=True
                    )
                    self.assertTrue(entry, script)
                    if entry.split()[0] == "100644":
                        self.assertTrue(
                            line[:match.start()].rstrip().endswith("bash"),
                            f"{caller}:{number} directly executes non-executable {script}",
                        )

    def test_service_restart_reenters_through_bash(self):
        source = (ROOT / "scripts" / "service.sh").read_text(encoding="utf-8")
        self.assertRegex(source, r'bash "\$0" stop "\$TARGET"')
        self.assertRegex(source, r'bash "\$0" start "\$TARGET" "\$RELEASE_ARG"')


if __name__ == "__main__":
    unittest.main()
