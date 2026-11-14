"""Transfer prepared outputs unchanged and fail closed when merging CI test shards."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import tarfile
import xml.etree.ElementTree as ET

MODULES = ('engine', 'gfc', 'plugin/gradle', 'plugin/maven')


def read(path):
    return json.loads(path.read_text())


def write(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2) + '\n')


def test_classes(root):
    result = {}
    for module in MODULES:
        names = read(root / module / 'build/test-inventory.json')
        if (not isinstance(names, list) or not names or
                any(not isinstance(name, str) or not name for name in names) or
                len(names) != len(set(names))):
            raise ValueError(f'Invalid discovered test inventory: {module}')
        result[module] = sorted(names)
    return result


def pack(root, archive):
    metadata = {'workspace': str(root.resolve()), 'tests': test_classes(root)}
    write(root / 'build/prepared.json', metadata)
    paths = [Path(m) / 'build' / d for m in MODULES for d in ('classes', 'resources', 'libs')]
    paths += [Path(module) / 'build/test-inventory.json' for module in MODULES]
    paths += [Path(p) for p in ('plugin/gradle/build/pluginUnderTestMetadata',
                               'engine/build/detection-fixtures', 'build/repository', 'build/prepared.json')]
    for path in paths[-4:]:
        if not (root / path).exists():
            raise ValueError(f'Missing prepared output: {path}')
    archive.parent.mkdir(parents=True, exist_ok=True)
    with tarfile.open(archive, 'w:gz') as output:
        for path in paths:
            if (root / path).exists():
                output.add(root / path, arcname=str(path))


def restore(root, archive):
    with tarfile.open(archive) as source:
        metadata = json.load(source.extractfile('build/prepared.json'))
        if metadata['workspace'] != str(root.resolve()):
            raise ValueError('Prepared outputs require the identical absolute workspace path')
        for member in source.getmembers():
            destination = root / member.name
            if not destination.resolve().is_relative_to(root.resolve()):
                raise ValueError('Unsafe archive path')
            if member.isdev() or member.isfifo():
                raise ValueError('Unsafe archive member')
            if member.issym() or member.islnk():
                target = (destination.parent if member.issym() else root) / member.linkname
                if not target.resolve().is_relative_to(root.resolve()):
                    raise ValueError('Unsafe archive link')
        source.extractall(root, filter='data')


def collect(root, output, shard, shards):
    if not 0 <= shard < shards:
        raise ValueError('Invalid shard identity')
    output.mkdir(parents=True, exist_ok=True)
    write(output / 'shard.json', {'shard': shard, 'shards': shards})
    for module in MODULES:
        for relative in ('build/jacoco/test.exec', 'build/test-results/test', 'build/reports/tests/test'):
            source = root / module / relative
            target = output / module / relative
            if source.is_dir():
                shutil.copytree(source, target, dirs_exist_ok=True)
            elif source.is_file():
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(source, target)
    source = root / 'engine/build/reports/detection'
    if source.exists():
        shutil.copytree(source, output / 'engine/build/reports/detection', dirs_exist_ok=True)


def assigned_classes(names, shard, shards):
    return {name for name in names if name == 'io.johnsonlee.graphfold.engine.DetectionCorpusTest'
            or int.from_bytes(hashlib.sha256(name.split('$')[0].encode('utf-8')).digest()[:4], 'big') % shards == shard}


def aggregate(root, inputs, output, shards):
    directories = sorted(inputs.iterdir())
    if {p.name for p in directories} != {f'shard-{i}' for i in range(shards)}:
        raise ValueError('Missing, duplicate or unexpected shard directories')
    expected_tests = read(root / 'build/prepared.json')['tests']
    observed_classes = {module: set() for module in MODULES}
    test_count = 0
    class_reports = {}
    class_shards = {}
    observed_by_shard = {}
    measurements = {'java': [], 'kotlin': []}
    for index in range(shards):
        directory = inputs / f'shard-{index}'
        if read(directory / 'shard.json') != {'shard': index, 'shards': shards}:
            raise ValueError('Duplicate or inconsistent shard identity')
        for module in MODULES:
            expected_classes = assigned_classes(expected_tests[module], index, shards)
            observed_by_shard[module, index] = set()
            execution = directory / module / 'build/jacoco/test.exec'
            if expected_classes and (not execution.is_file() or execution.stat().st_size == 0):
                raise ValueError(f'Missing coverage execution data: {execution}')
            reports = sorted((directory / module / 'build/test-results/test').glob('TEST-*.xml'))
            for report in reports:
                suite = ET.parse(report).getroot()
                cases = suite.findall('testcase')
                if suite.tag != 'testsuite' or not cases or int(suite.get('tests', '-1')) != len(cases):
                    raise ValueError(f'Incomplete JUnit report: {report}')
                if any(int(suite.get(key, '-1')) != 0 for key in ('failures', 'errors', 'skipped')):
                    raise ValueError(f'Unsuccessful JUnit report: {report}')
                for case in cases:
                    if any(case.find(tag) is not None for tag in ('failure', 'error', 'skipped')):
                        raise ValueError(f'Unsuccessful testcase: {report}')
                    classname, display_name = case.get('classname'), case.get('name')
                    if not classname or not display_name:
                        raise ValueError(f'Invalid testcase: {report}')
                    # Parameterized methods can share a display name. The XML does not
                    # expose their method identity, so count occurrences within one report.
                    owner_report = class_reports.setdefault((module, index, classname), report)
                    if owner_report != report:
                        raise ValueError(f'Duplicate class report: {module}/{classname}')
                    test_count += 1
                    name = classname.split('$')[0]
                    owner = class_shards.setdefault((module, name), index)
                    if owner != index and name != 'io.johnsonlee.graphfold.engine.DetectionCorpusTest':
                        raise ValueError(f'Test class executed in multiple shards: {module}/{name}')
                    observed_classes[module].add(name)
                    observed_by_shard[module, index].add(name)
        for language in measurements:
            path = directory / 'engine/build/reports/detection' / language
            rows = read(path / 'results.json')
            metrics = read(path / 'metrics.json')
            if not isinstance(rows, list) or not rows or metrics.get('cases') != len(rows) or metrics.get('filtered') is not False:
                raise ValueError(f'Partial corpus report: {path}')
            if metrics.get('complete') is not True or metrics.get('shard') != index or metrics.get('shards') != shards:
                raise ValueError(f'Incomplete or misidentified corpus shard: {path}')
            true_positive = sum(row['expectedViolation'] and row['predictedViolation'] for row in rows)
            false_positive = sum(not row['expectedViolation'] and row['predictedViolation'] for row in rows)
            false_negative = sum(row['expectedViolation'] and not row['predictedViolation'] for row in rows)
            counters = {'truePositive': true_positive, 'falsePositive': false_positive, 'falseNegative': false_negative}
            if any(metrics.get(key) != value for key, value in counters.items()):
                raise ValueError(f'Inconsistent corpus metrics: {path}')
            measurements[language].extend(rows)
    for module in MODULES:
        for index in range(shards):
            expected_classes = assigned_classes(expected_tests[module], index, shards)
            if expected_classes != observed_by_shard[module, index]:
                raise ValueError(f'Incomplete test classes for {module} shard {index}: expected {sorted(expected_classes)}, observed {sorted(observed_by_shard[module, index])}')
        if set(expected_tests[module]) != observed_classes[module]:
            raise ValueError(f'Incomplete test classes for {module}: expected {expected_tests[module]}, observed {sorted(observed_classes[module])}')
    common = read(root / 'fixtures/manifest.json')
    total = 0
    for language, rows in measurements.items():
        expected_rows = common + (read(root / 'fixtures/kotlin/features.json') if language == 'kotlin' else [])
        expected = {case['id']: case['violation'] for case in expected_rows}
        ids = [row['id'] for row in rows]
        if len(expected) != len(expected_rows) or len(ids) != len(set(ids)) or set(ids) != set(expected):
            raise ValueError(f'Incomplete or duplicate {language} corpus identities')
        for row in rows:
            violation = expected[row['id']]
            result = row['result']
            if (row['expectedViolation'] is not violation or row['predictedViolation'] is not violation
                    or result['verdict'] != ('FAIL' if violation else 'PASS')
                    or any(item['blocking'] for item in result['diagnostics'])):
                raise ValueError(f'Incorrect or operationally failed corpus result: {language}/{row["id"]}')
        positives = sum(expected.values())
        write(output / language / 'results.json', sorted(rows, key=lambda row: row['id']))
        write(output / language / 'metrics.json', {'filtered': False, 'cases': len(rows),
              'truePositive': positives, 'falsePositive': 0, 'falseNegative': 0,
              'precision': 1.0, 'recall': 1.0})
        total += len(rows)
    summary = {'shards': shards, 'tests': test_count, 'corpusCases': total,
               'modules': {module: len(names) for module, names in observed_classes.items()}}
    write(output / 'summary.json', summary)
    lines = [f"Verified {test_count} tests across {shards} shards and {total} corpus cases.", "",
             "| Module | Test classes |", "| --- | ---: |"]
    lines.extend(f"| {module} | {count} |" for module, count in summary['modules'].items())
    (output / 'summary.md').write_text('\n'.join(lines) + '\n')
    print(json.dumps(summary, indent=2))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=Path.cwd())
    commands = parser.add_subparsers(dest='command', required=True)
    for command in ('pack', 'restore'):
        commands.add_parser(command).add_argument('--archive', type=Path, required=True)
    collection = commands.add_parser('collect')
    collection.add_argument('--shard', type=int, required=True)
    merging = commands.add_parser('aggregate')
    merging.add_argument('--input', type=Path, required=True)
    for command in (collection, merging):
        command.add_argument('--shards', type=int, required=True)
        command.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if args.command == 'pack':
        pack(args.root, args.archive)
    elif args.command == 'restore':
        restore(args.root, args.archive)
    elif args.command == 'collect':
        collect(args.root, args.output, args.shard, args.shards)
    else:
        aggregate(args.root, args.input, args.output, args.shards)


if __name__ == '__main__':
    main()
