#!/usr/bin/env bash
#
# Runs the single-module change gate over a commit range.
#
# INVERTED=1 flips the exit code, which is how MOD-02 and MOD-03 are exercised: a test asserts that
# a deliberately out-of-scope range fails the gate, and asserting on a non-zero exit would make the
# test itself look like a failure to the build.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
INVERTED="${INVERTED:-0}"

python3 "$REPO_ROOT/scripts/check_change_scope.py" --repo "$REPO_ROOT" "$@" || status=$?
status="${status:-0}"

if [ "$INVERTED" = "1" ]; then
  # Gate result is reported through the exit code only; the JSON was already printed.
  [ "$status" -eq 0 ] && exit 1 || exit 0
fi
exit "$status"
