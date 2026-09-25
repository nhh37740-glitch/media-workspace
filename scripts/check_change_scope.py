"""Single-module change gate. Run against a real Git commit range; read-only.

Copied from the plan package (计划书/scripts/check_change_scope.py) so Jenkins can run it from the
repository. The only addition is CLAUDE.md, which belongs to the engineering module the same way
README.md does. The rules and the ownership table are otherwise unchanged.
"""
import argparse
import json
import subprocess
import sys
from pathlib import PurePosixPath

MODULES = {
    "media-contracts", "media-domain", "media-application",
    "adapter-persistence", "adapter-messaging", "adapter-transcode",
    "adapter-storage", "media-api", "media-worker", "web", "build-delivery",
}
BUILD_ROOT_FILES = {
    "settings.gradle", "settings.gradle.kts", "build.gradle", "build.gradle.kts",
    "gradle.properties", "gradlew", "gradlew.bat", "Jenkinsfile", ".gitignore",
    ".gitattributes", "README.md", "CLAUDE.md",
}


def owner(path):
    p = PurePosixPath(path)
    if p.is_absolute() or ".." in p.parts or "\\" in path:
        return None
    parts = p.parts
    if not parts:
        return None
    if parts[0] in MODULES - {"build-delivery"}:
        return parts[0]
    if parts[0] == "contracts":
        return "media-contracts"
    if parts[0] in {"scripts", "deploy", "gradle"} or path in BUILD_ROOT_FILES:
        return "build-delivery"
    if parts[:2] == ("tests", "e2e"):
        return "build-delivery"
    return None


def check_paths(paths, module, limit=8):
    paths = sorted(set(paths))
    errors = []
    if module not in MODULES:
        errors.append("Unknown module")
    if not paths:
        errors.append("No changes in range")
    if len(paths) > limit:
        errors.append(f"Changed files {len(paths)} exceeds limit {limit}")
    for path in paths:
        parts = PurePosixPath(path).parts
        diagnostic = (len(parts) >= 3 and parts[:2] in {
            ("docs", "diagnostics"), ("docs", "handoffs")
        } and ".." not in parts and "\\" not in path)
        if not diagnostic and owner(path) != module:
            errors.append(f"Outside module {module}: {path}")
    return errors


def git(repo, *args):
    return subprocess.check_output(["git", "-C", repo, *args], stderr=subprocess.PIPE)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", default=".")
    parser.add_argument("--base", required=True)
    parser.add_argument("--head", required=True)
    parser.add_argument("--module", required=True, choices=sorted(MODULES))
    args = parser.parse_args()
    try:
        base = git(args.repo, "rev-parse", "--verify", "--end-of-options", args.base + "^{commit}").decode().strip()
        head = git(args.repo, "rev-parse", "--verify", "--end-of-options", args.head + "^{commit}").decode().strip()
        git(args.repo, "merge-base", "--is-ancestor", base, head)
        # No rename collapsing: a cross-module move checks both old and new paths.
        raw = git(args.repo, "diff", "--no-renames", "--name-only", "-z", base, head, "--")
        paths = [x.decode("utf-8", errors="strict") for x in raw.split(b"\0") if x]
        errors = check_paths(paths, args.module)
        # Submodules and symlinks cannot redirect a module boundary outside the tree.
        tree = git(args.repo, "ls-tree", "-r", "-z", head, "--")
        for entry in tree.split(b"\0"):
            if not entry:
                continue
            meta, path = entry.split(b"\t", 1)
            if meta.split()[0] in {b"120000", b"160000"} and path.decode("utf-8") in paths:
                errors.append("Changed symlink/submodule not allowed: " + path.decode("utf-8"))
    except (subprocess.CalledProcessError, UnicodeError, ValueError) as exc:
        print(json.dumps({"passed": False, "error": str(exc)}, ensure_ascii=False))
        return 2
    print(json.dumps({"passed": not errors, "module": args.module, "base": base,
                      "head": head, "files": paths, "errors": errors}, ensure_ascii=False, indent=2))
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
