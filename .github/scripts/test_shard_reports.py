import contextlib
import io
import json
from pathlib import Path
import tempfile
import shutil
import unittest

from test_shards import MODULES, aggregate, pack, read, restore, write


class ShardReportsTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.inputs = self.root / 'shards'
        self.output = self.root / 'merged'
        write(self.root / 'build/prepared.json', {'tests': {m: ['sample.Example0Test', 'sample.Example1Test'] for m in MODULES}})
        write(self.root / 'fixtures/manifest.json', [{'id': 'positive', 'violation': True}, {'id': 'negative', 'violation': False}])
        write(self.root / 'fixtures/kotlin/features.json', [])
        for shard in range(2):
            directory = self.inputs / f'shard-{shard}'
            write(directory / 'shard.json', {'shard': shard, 'shards': 2})
            for module in MODULES:
                execution = directory / module / 'build/jacoco/test.exec'
                execution.parent.mkdir(parents=True)
                execution.write_bytes(b'execution data')
                report = directory / module / 'build/test-results/test/TEST-example.xml'
                report.parent.mkdir(parents=True)
                report.write_text(f'<testsuite tests="1" failures="0" errors="0" skipped="0"><testcase classname="sample.Example{shard}Test" name="case{shard}"/></testsuite>')
            for language in ('java', 'kotlin'):
                path = directory / 'engine/build/reports/detection' / language
                violation = shard == 0
                write(path / 'results.json', [{'id': 'positive' if violation else 'negative',
                      'expectedViolation': violation, 'predictedViolation': violation,
                      'result': {'verdict': 'FAIL' if violation else 'PASS', 'diagnostics': []}}])
                write(path / 'metrics.json', {'filtered': False, 'complete': True, 'shard': shard, 'shards': 2, 'cases': 1, 'truePositive': int(violation), 'falsePositive': 0, 'falseNegative': 0})

    def merge(self):
        with contextlib.redirect_stdout(io.StringIO()):
            aggregate(self.root, self.inputs, self.output, 2)

    def test_complete_inputs_merge(self):
        self.merge()
        self.assertEqual(4, read(self.output / 'summary.json')['corpusCases'])
        self.assertEqual(8, read(self.output / 'summary.json')['tests'])

    def test_missing_or_extra_shard(self):
        (self.inputs / 'shard-1').rename(self.inputs / 'shard-2')
        with self.assertRaisesRegex(ValueError, 'shard directories'):
            self.merge()

    def test_duplicate_shard_identity(self):
        write(self.inputs / 'shard-1/shard.json', {'shard': 0, 'shards': 2})
        with self.assertRaisesRegex(ValueError, 'shard identity'):
            self.merge()

    def test_failed_error_and_skipped_tests_rejected(self):
        report = self.inputs / 'shard-0/engine/build/test-results/test/TEST-example.xml'
        original = report.read_text()
        for status in ('failures', 'errors', 'skipped'):
            with self.subTest(status=status):
                report.write_text(original.replace(f'{status}="0"', f'{status}="1"'))
                with self.assertRaisesRegex(ValueError, 'Unsuccessful'):
                    self.merge()

    def test_partial_xml_rejected(self):
        report = self.inputs / 'shard-0/engine/build/test-results/test/TEST-example.xml'
        report.write_text(report.read_text().replace('tests="1"', 'tests="2"'))
        with self.assertRaisesRegex(ValueError, 'Incomplete JUnit'):
            self.merge()

    def test_missing_entire_test_class_rejected(self):
        metadata = read(self.root / 'build/prepared.json')
        metadata['tests']['engine'].append('sample.MissingTest')
        write(self.root / 'build/prepared.json', metadata)
        with self.assertRaisesRegex(ValueError, 'Incomplete test classes'):
            self.merge()

    def test_duplicate_testcase_in_another_shard_rejected(self):
        report = self.inputs / 'shard-1/engine/build/test-results/test/TEST-example.xml'
        report.write_text(report.read_text().replace('case1', 'case0').replace('Example1Test', 'Example0Test'))
        with self.assertRaisesRegex(ValueError, 'multiple shards'):
            self.merge()

    def test_parameterized_methods_with_identical_display_names_count_separately(self):
        report = self.inputs / 'shard-0/engine/build/test-results/test/TEST-example.xml'
        # Gradle emits the same display name for different parameterized methods.
        report.write_text('<testsuite tests="2" failures="0" errors="0" skipped="0">'
                          '<testcase classname="sample.Example0Test" name="[2] 0, 0"/>'
                          '<testcase classname="sample.Example0Test" name="[2] 0, 0"/>'
                          '</testsuite>')
        self.merge()
        self.assertEqual(9, read(self.output / 'summary.json')['tests'])

    def test_duplicate_class_report_rejected_even_with_distinct_display_names(self):
        report = self.inputs / 'shard-0/engine/build/test-results/test/TEST-example.xml'
        report.with_name('TEST-duplicate.xml').write_text(report.read_text().replace('case0', 'another case'))
        with self.assertRaisesRegex(ValueError, 'Duplicate class report'):
            self.merge()

    def test_missing_coverage_rejected(self):
        (self.inputs / 'shard-0/gfc/build/jacoco/test.exec').unlink()
        with self.assertRaisesRegex(ValueError, 'Missing coverage'):
            self.merge()

    def test_partial_corpus_rejected(self):
        path = self.inputs / 'shard-0/engine/build/reports/detection/java/results.json'
        write(path, [])
        with self.assertRaisesRegex(ValueError, 'Partial corpus'):
            self.merge()

    def test_duplicate_corpus_case_rejected(self):
        path = self.inputs / 'shard-1/engine/build/reports/detection/java/results.json'
        write(path, read(self.inputs / 'shard-0/engine/build/reports/detection/java/results.json'))
        metrics = read(path.with_name('metrics.json'))
        metrics['truePositive'] = 1
        write(path.with_name('metrics.json'), metrics)
        with self.assertRaisesRegex(ValueError, 'duplicate java corpus'):
            self.merge()

    def test_operational_failure_and_wrong_verdict_rejected(self):
        path = self.inputs / 'shard-0/engine/build/reports/detection/java/results.json'
        original = read(path)
        for result in ({'verdict': 'FAIL', 'diagnostics': [{'blocking': True}]},
                       {'verdict': 'ERROR', 'diagnostics': []}, {'verdict': 'PASS', 'diagnostics': []}):
            with self.subTest(result=result):
                original[0]['result'] = result
                write(path, original)
                with self.assertRaisesRegex(ValueError, 'Incorrect or operationally failed'):
                    self.merge()

    def test_filtered_incomplete_or_inconsistent_metrics_rejected(self):
        path = self.inputs / 'shard-0/engine/build/reports/detection/java/metrics.json'
        original = read(path)
        for key, value in (('filtered', True), ('complete', False), ('shard', 1), ('truePositive', 0)):
            with self.subTest(key=key):
                write(path, {**original, key: value})
                with self.assertRaises(ValueError):
                    self.merge()

    def test_class_repeated_across_shards_rejected(self):
        report = self.inputs / 'shard-1/engine/build/test-results/test/TEST-example.xml'
        report.write_text(report.read_text().replace('Example1Test', 'Example0Test'))
        with self.assertRaisesRegex(ValueError, 'multiple shards'):
            self.merge()

    def test_manifest_case_missing_from_reports_rejected(self):
        path = self.root / 'fixtures/manifest.json'
        write(path, read(path) + [{'id': 'missing', 'violation': False}])
        with self.assertRaisesRegex(ValueError, 'Incomplete or duplicate'):
            self.merge()

    def test_legitimately_empty_module_shard_needs_no_execution_data(self):
        metadata = read(self.root / 'build/prepared.json')
        metadata['tests']['plugin/maven'] = ['sample.Example0Test']
        write(self.root / 'build/prepared.json', metadata)
        shutil.rmtree(self.inputs / 'shard-1/plugin/maven')
        self.merge()
        self.assertEqual(7, read(self.output / 'summary.json')['tests'])

    def test_nonempty_module_shard_cannot_omit_all_outputs(self):
        shutil.rmtree(self.inputs / 'shard-1/plugin/maven')
        with self.assertRaisesRegex(ValueError, 'Missing coverage'):
            self.merge()

    def test_class_in_wrong_shard_rejected(self):
        first = self.inputs / 'shard-0/plugin/maven/build/test-results/test/TEST-example.xml'
        second = self.inputs / 'shard-1/plugin/maven/build/test-results/test/TEST-example.xml'
        contents = first.read_text()
        first.write_text(second.read_text())
        second.write_text(contents)
        with self.assertRaisesRegex(ValueError, 'Incomplete test classes'):
            self.merge()

    def test_pack_restore_preserves_hidden_git_and_rejects_relocation(self):
        for module in MODULES:
            write(self.root / module / 'build/test-inventory.json', ['sample.ExampleTest'])
        for name in ('plugin/gradle/build/pluginUnderTestMetadata', 'build/repository'):
            (self.root / name).mkdir(parents=True, exist_ok=True)
        git = self.root / 'engine/build/detection-fixtures/java/example/.git/config'
        git.parent.mkdir(parents=True)
        git.write_text('fixture repository')
        git.chmod(0o755)
        archive = self.root / 'prepared.tar.gz'
        pack(self.root, archive)
        git.unlink()
        for module in MODULES:
            (self.root / module / 'build/test-inventory.json').unlink()
        restore(self.root, archive)
        for module in MODULES:
            self.assertEqual(['sample.ExampleTest'], read(self.root / module / 'build/test-inventory.json'))
        self.assertEqual('fixture repository', git.read_text())
        self.assertEqual(0o755, git.stat().st_mode & 0o777)
        with self.assertRaisesRegex(ValueError, 'identical absolute workspace'):
            restore(self.root / 'relocated', archive)


if __name__ == '__main__':
    unittest.main()
