"""Check that services started by Jenkins outlive the build's process cookie."""

import os
import subprocess
import tempfile
import time
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parent.parent
SERVICE = ROOT / "scripts" / "service.sh"


class ServiceJenkinsCookieTest(unittest.TestCase):
    def test_java_launch_overrides_only_the_build_cookies(self):
        source = SERVICE.read_text(encoding="utf-8")
        self.assertIn("JENKINS_NODE_COOKIE=dontKillMe BUILD_ID=dontKillMe", source)
        self.assertNotIn("export JENKINS_NODE_COOKIE", source)
        self.assertNotIn("export BUILD_ID", source)

    @unittest.skipUnless(os.name == "posix", "requires Linux /proc and POSIX process modes")
    def test_isolated_java_child_does_not_inherit_build_cookies(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            java = root / "jdk" / "bin" / "java"
            java.parent.mkdir(parents=True)
            java.write_text(
                '#!/bin/sh\n'
                'printf "%s\\n%s\\n" "$JENKINS_NODE_COOKIE" "$BUILD_ID" > "$MW_COOKIE_PROBE"\n'
                'exec sleep 3\n',
                encoding="utf-8",
            )
            java.chmod(0o755)

            release = root / "release"
            (release / "apps").mkdir(parents=True)
            (release / "config").mkdir()
            (release / "apps" / "media-api-0.jar").touch()
            (release / "config" / "api.opts").write_text("\n", encoding="utf-8")
            config = root / "media-workspace.env"
            config.write_text("DB_PASSWORD=test\nSTORAGE_ROOT=/tmp\n", encoding="utf-8")
            probe = root / "cookie-probe"

            environment = os.environ.copy()
            environment.update({
                "DEPLOY_ROOT": str(root),
                "MW_ENV_FILE": str(config),
                "JAVA_HOME": str(root / "jdk"),
                "MW_COOKIE_PROBE": str(probe),
                "JENKINS_NODE_COOKIE": "this-build-node-cookie",
                "BUILD_ID": "this-build-id",
            })
            result = subprocess.run(
                ["bash", str(SERVICE), "start", "api", str(release)],
                env=environment,
                capture_output=True,
                text=True,
                timeout=10,
                check=False,
            )
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            deadline = time.monotonic() + 2
            while not probe.exists() and time.monotonic() < deadline:
                time.sleep(0.05)
            self.assertTrue(probe.exists(), result.stdout + result.stderr)
            self.assertEqual(["dontKillMe", "dontKillMe"], probe.read_text().splitlines())
            time.sleep(3)  # Let the isolated fake process exit before removing its directory.


if __name__ == "__main__":
    unittest.main()
