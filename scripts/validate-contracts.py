#!/usr/bin/env python3
"""Validates the machine-readable contracts and the test specification references.

Checks, in order:
  1. every JSON file under contracts/ parses;
  2. the event envelope schema is a valid JSON Schema document;
  3. every example event validates against it, and a deliberately broken one does not, so the
     schema is shown to reject something rather than merely to load;
  4. every test id referenced from the feature file exists in the test matrix document.

The last check is what keeps the behaviour specification and the case matrix from drifting apart:
a scenario tagged with an id nobody implemented is a specification nobody is honouring.

Exits non-zero on the first failing category, listing every problem found in it.
"""
import argparse
import json
import re
import sys
from pathlib import Path

try:
    import jsonschema
except ImportError:  # pragma: no cover - the message is the point
    print("ERROR: the jsonschema package is required (pip install jsonschema)", file=sys.stderr)
    sys.exit(2)


def load_json(path):
    with path.open(encoding='utf-8') as handle:
        return json.load(handle)


def check_json_parses(contracts_dir, problems):
    for path in sorted(contracts_dir.glob('**/*.json')):
        try:
            load_json(path)
        except json.JSONDecodeError as error:
            problems.append(f"{path}: not valid JSON: {error}")


def check_schema_and_examples(contracts_dir, problems):
    schema_path = contracts_dir / 'event-envelope.schema.json'
    if not schema_path.exists():
        problems.append(f"{schema_path} is missing")
        return None

    schema = load_json(schema_path)
    try:
        validator_class = jsonschema.validators.validator_for(schema)
        validator_class.check_schema(schema)
    except jsonschema.SchemaError as error:
        problems.append(f"{schema_path}: not a valid schema: {error.message}")
        return None

    validator = validator_class(schema)

    examples = [p for p in sorted(contracts_dir.glob('*.example.json'))]
    if not examples:
        problems.append("no example event is present; a schema with no example is unexercised")
    for path in examples:
        document = load_json(path)
        errors = sorted(validator.iter_errors(document), key=lambda e: list(e.absolute_path))
        for error in errors:
            location = '/'.join(str(part) for part in error.absolute_path) or '(root)'
            problems.append(f"{path.name}: {location}: {error.message}")

    # The schema must reject, not only accept. Each case removes a required member or sets an
    # unsupported value, and every one of them has to fail.
    negatives = [
        ("a missing traceId", lambda d: d.pop('traceId', None)),
        ("a missing producerInvocationId", lambda d: d.pop('producerInvocationId', None)),
        ("an unsupported schemaVersion", lambda d: d.update(schemaVersion=99)),
        ("an unknown eventType", lambda d: d.update(eventType='TASK_EXPLODED')),
        ("an empty payload", lambda d: d.update(payload={})),
    ]
    if examples:
        base = load_json(examples[0])
        for description, mutate in negatives:
            candidate = json.loads(json.dumps(base))
            mutate(candidate)
            if validator.is_valid(candidate):
                problems.append(f"the schema accepted {description}, which it must reject")

    return validator


def check_feature_references(feature_path, matrix_path, problems):
    if not feature_path.exists() or not matrix_path.exists():
        problems.append("the feature file or the case matrix is missing")
        return

    feature_text = feature_path.read_text(encoding='utf-8')
    matrix_text = matrix_path.read_text(encoding='utf-8')

    referenced = set(re.findall(r'@([A-Z]+-\d+)', feature_text))
    if not referenced:
        problems.append(f"{feature_path.name}: no tagged scenario found")
    for tag in sorted(referenced):
        if tag not in matrix_text:
            problems.append(f"{feature_path.name}: tag {tag} has no case in the matrix document")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--contracts', default='contracts')
    parser.add_argument('--feature', default='docs/acceptance.feature')
    parser.add_argument('--matrix', default='docs/test-matrix.md')
    args = parser.parse_args()

    contracts_dir = Path(args.contracts)
    if not contracts_dir.is_dir():
        print(f"ERROR: {contracts_dir} does not exist", file=sys.stderr)
        return 1

    problems = []
    check_json_parses(contracts_dir, problems)
    if not problems:
        check_schema_and_examples(contracts_dir, problems)
    if not problems:
        check_feature_references(Path(args.feature), Path(args.matrix), problems)

    if problems:
        for problem in problems:
            print(f"ERROR: {problem}", file=sys.stderr)
        return 1
    print("contracts validated: schema, examples, rejections and feature references")
    return 0


if __name__ == '__main__':
    sys.exit(main())
