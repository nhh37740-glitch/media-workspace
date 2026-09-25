#!/usr/bin/env python3
"""Counts executed tests across the JUnit XML reports a build produced.

A pipeline that reports "BUILD SUCCESSFUL" while running no tests is the failure this guards
against. With --require-nonzero the script exits non-zero when the total is zero, so the stage
fails rather than passing on an empty result set.
"""
import argparse
import glob
import sys
import xml.etree.ElementTree as ET


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--pattern', default='**/build/test-results/**/*.xml',
                        help='glob for JUnit XML reports, relative to the current directory')
    parser.add_argument('--require-nonzero', action='store_true',
                        help='fail when no test was executed')
    parser.add_argument('--min-tests', type=int, default=0,
                        help='fail when fewer than this many tests ran')
    parser.add_argument('--max-skipped', type=int, default=None,
                        help='fail when more than this many tests were skipped')
    args = parser.parse_args()

    total = failures = errors = skipped = 0
    reports = 0
    for path in glob.glob(args.pattern, recursive=True):
        try:
            root = ET.parse(path).getroot()
        except ET.ParseError:
            continue
        reports += 1
        total += int(root.get('tests', 0))
        failures += int(root.get('failures', 0))
        errors += int(root.get('errors', 0))
        skipped += int(root.get('skipped', 0))

    print(f"test reports: {reports}")
    print(f"tests: {total}  failures: {failures}  errors: {errors}  skipped: {skipped}")

    problems = []
    if args.require_nonzero and total == 0:
        problems.append("no test was executed; a build with an empty result set is not a pass")
    if total < args.min_tests:
        problems.append(f"only {total} test(s) ran, fewer than the required {args.min_tests}")
    if failures or errors:
        problems.append(f"{failures + errors} test(s) failed or errored")
    if args.max_skipped is not None and skipped > args.max_skipped:
        problems.append(f"{skipped} test(s) were skipped, more than the allowed {args.max_skipped}")

    if problems:
        for problem in problems:
            print(f"ERROR: {problem}", file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
