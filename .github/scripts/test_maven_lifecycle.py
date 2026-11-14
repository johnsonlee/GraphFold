import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as ET

SCRIPT = Path(__file__).resolve().parents[2] / 'tools/verification/verify_maven_lifecycle.py'
SPEC = importlib.util.spec_from_file_location('maven_lifecycle', SCRIPT)
harness = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(harness)


class MavenLifecycleAcceptanceTest(unittest.TestCase):
    def test_fixtures_use_real_packaging_plugins_and_both_repository_kinds(self):
        namespace = {'m': 'http://maven.apache.org/POM/4.0.0'}
        for layout in harness.LAYOUTS:
            root = ET.fromstring(harness.pom(layout, 'file:///tmp/published-artifacts'))
            self.assertEqual('war' if layout == 'war' else 'jar', root.find('m:packaging', namespace).text)
            for kind in ['repositories', 'pluginRepositories']:
                entries = root.find('m:' + kind, namespace)
                self.assertEqual({'graphfold-verification', 'central', 'graphitePackages'},
                                 {entry.find('m:id', namespace).text for entry in entries})
            plugins = {entry.find('m:artifactId', namespace).text: entry
                       for entry in root.findall('m:build/m:plugins/m:plugin', namespace)}
            self.assertIn('maven-compiler-plugin', plugins)
            if layout == 'boot':
                self.assertEqual('repackage', plugins['spring-boot-maven-plugin'].find(
                    'm:executions/m:execution/m:goals/m:goal', namespace).text)
            if layout == 'war':
                self.assertIn('maven-war-plugin', plugins)

    def test_accepts_proved_positive_and_located_negative(self):
        for verdict in ['PASS', 'FAIL']:
            with tempfile.TemporaryDirectory() as temporary:
                path = Path(temporary) / 'report.json'
                path.write_text(json.dumps(self.report(verdict)))
                self.assertEqual(verdict, harness.check_report(path, verdict)['verdict'])

    def test_diagnostic_only_failure_or_missing_graph_cannot_pass_the_golden(self):
        examples = []
        blocked = self.report('FAIL')
        blocked['findings'] = []
        blocked['diagnostics'] = [{'code': 'ANALYSIS_FAILED'}]
        examples.append(blocked)
        missing_graph = self.report('PASS')
        missing_graph['ruleAudits'].pop()
        examples.append(missing_graph)
        unproved = self.report('PASS')
        unproved['metadata']['provedObligations'] = 0
        examples.append(unproved)
        wrong_source = self.report('FAIL')
        wrong_source['findings'][0]['locations'][0]['path'] = 'Other.java'
        examples.append(wrong_source)
        for result in examples:
            with tempfile.TemporaryDirectory() as temporary:
                path = Path(temporary) / 'report.json'
                path.write_text(json.dumps(result))
                with self.assertRaises(AssertionError):
                    harness.check_report(path, result['verdict'])

    def test_serialized_reactor_poms_keep_maven_default_namespace(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            harness.reactor_fixture(root, 'file:///tmp/published-artifacts')
            for path in [root / 'pom.xml', root / 'one/pom.xml', root / 'two/pom.xml']:
                document = path.read_text()
                self.assertTrue(document.startswith('<project xmlns="http://maven.apache.org/POM/4.0.0">'), document)
                self.assertNotIn('<ns0:', document)
                project = ET.fromstring(document)
                self.assertEqual('{http://maven.apache.org/POM/4.0.0}project', project.tag)
                self.assertEqual('4.0.0', project.find('{http://maven.apache.org/POM/4.0.0}modelVersion').text)

    def test_reactor_has_distinct_policies_and_selected_modules(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            harness.reactor_fixture(root, 'file:///tmp/published-artifacts')
            namespace = {'m': 'http://maven.apache.org/POM/4.0.0'}
            project = ET.parse(root / 'pom.xml').getroot()
            self.assertEqual('pom', project.find('m:packaging', namespace).text)
            self.assertEqual(['one', 'two'], [module.text for module in project.findall('m:modules/m:module', namespace)])
            for index, name in enumerate(('one', 'two'), start=1):
                child = ET.parse(root / name / 'pom.xml').getroot()
                self.assertEqual(name, child.find('m:artifactId', namespace).text)
                self.assertIn(': ' + str(index * 10000), (root / name / 'src/main/java/sample/Quote.java').read_text())

    def test_reactor_rejects_swapped_or_collapsed_manifests(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / 'repository'
            snapshots = Path(temporary) / 'snapshots'
            paths = []
            for name in ('one', 'two'):
                source = str(root / name / 'src/main/java')
                path = snapshots / 'modules' / ('fixture%3A' + name) / 'snapshot.json'
                harness.write(path, json.dumps(dict(sha='base', scopes=[dict(id='fixture:' + name,
                    javaVersion=17, sourceRoots=[source], sourceOutputs={source: []})])))
                paths.append(path)
            harness.check_reactor_snapshots(snapshots, 'base', root)
            one, two = (path.read_text() for path in paths)
            paths[0].write_text(two)
            paths[1].write_text(one)
            with self.assertRaises(AssertionError):
                harness.check_reactor_snapshots(snapshots, 'base', root)
            paths[0].write_text(one)
            paths[1].unlink()
            with self.assertRaises(AssertionError):
                harness.check_reactor_snapshots(snapshots, 'base', root)

    def test_reactor_findings_must_belong_to_the_expected_module(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / 'report.json'
            report = self.report('FAIL')
            report['findings'][0]['locations'][0]['path'] = 'two/src/main/java/sample/Quote.java'
            path.write_text(json.dumps(report))
            harness.check_report(path, 'FAIL', 'two/src/main/java/sample/Quote.java')
            with self.assertRaises(AssertionError):
                harness.check_report(path, 'FAIL', 'one/src/main/java/sample/Quote.java')

    @staticmethod
    def report(verdict):
        return dict(verdict=verdict, diagnostics=[],
                    metadata=dict(obligations=1, provedObligations=1 if verdict == 'PASS' else 0),
                    ruleAudits=[dict(selectionStatus='SELECTED', matchedCallCount=1) for _ in range(2)],
                    findings=[] if verdict == 'PASS' else [dict(locations=[dict(path='src/main/java/sample/Quote.java')])])


if __name__ == '__main__':
    unittest.main()
