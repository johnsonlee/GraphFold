#!/usr/bin/env python3
"""Verify published GraphFold Mojos through real Maven packaging and check lifecycles.

No application or container is started. Verdict expectations come from static control coverage.
All generated fixtures, downloads, logs and reports live under the required output directory.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tarfile
import urllib.request
import xml.etree.ElementTree as ET
import zipfile

MAVEN_VERSION = '3.9.9'
MAVEN_URL = f'https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/{MAVEN_VERSION}/apache-maven-{MAVEN_VERSION}-bin.tar.gz'
# Official Maven Central checksum, pinned independently of the downloaded archive.
MAVEN_SHA512 = ('a555254d6b53d267965a3404ecb14e53c3827c09c3b94b5678835887ab404556bfaf78dcfe03ba76fa2508649'
                'dca8531c74bca4d5846513522404d48e8c4ac8b')
GRAPHITE_REPOSITORY = 'https://maven.pkg.github.com/johnsonlee/graphite'
GROUP = 'io.johnsonlee.graphfold'
BOOT_VERSION = '3.5.7'
LAYOUTS = ('library', 'boot', 'war')


def write(path, text):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding='utf-8')


def run(command, cwd, log, expected=0, timeout=600):
    with log.open('a', encoding='utf-8') as output:
        result = subprocess.run(command, cwd=cwd, stdout=output, stderr=subprocess.STDOUT,
                                timeout=timeout, check=False)
    if result.returncode != expected:
        raise RuntimeError(f'Command exited {result.returncode}, expected {expected}; inspect {log}')


def maven(output):
    archive = output / f'apache-maven-{MAVEN_VERSION}-bin.tar.gz'
    with urllib.request.urlopen(MAVEN_URL, timeout=60) as source, archive.open('wb') as target:
        shutil.copyfileobj(source, target)
    if hashlib.sha512(archive.read_bytes()).hexdigest() != MAVEN_SHA512:
        raise RuntimeError('Maven distribution checksum differs from pinned official SHA-512')
    with tarfile.open(archive, 'r:gz') as package:
        for member in package.getmembers():
            target = (output / member.name).resolve()
            if not target.is_relative_to(output.resolve()) or member.issym() or member.islnk():
                raise RuntimeError('Maven archive contains an unsafe member')
        package.extractall(output)
    return output / f'apache-maven-{MAVEN_VERSION}' / 'bin' / 'mvn'


def repositories(parent, element, publication):
    block = ET.SubElement(parent, element)
    tag = 'pluginRepository' if element == 'pluginRepositories' else 'repository'
    for identity, url in [('graphfold-verification', publication), ('central', 'https://repo.maven.apache.org/maven2'),
                          ('graphitePackages', GRAPHITE_REPOSITORY)]:
        repo = ET.SubElement(block, tag)
        ET.SubElement(repo, 'id').text = identity
        ET.SubElement(repo, 'url').text = url


def pom(layout, publication):
    project = ET.Element('project', xmlns='http://maven.apache.org/POM/4.0.0')
    for name, value in [('modelVersion', '4.0.0'), ('groupId', 'fixture'), ('artifactId', layout),
                        ('version', '1'), ('packaging', 'war' if layout == 'war' else 'jar')]:
        ET.SubElement(project, name).text = value
    properties = ET.SubElement(project, 'properties')
    ET.SubElement(properties, 'maven.compiler.release').text = '17'
    ET.SubElement(properties, 'project.build.sourceEncoding').text = 'UTF-8'
    repositories(project, 'repositories', publication)
    repositories(project, 'pluginRepositories', publication)
    dependencies = ET.SubElement(project, 'dependencies')
    if layout == 'boot':
        dependency(dependencies, 'org.springframework.boot', 'spring-boot', BOOT_VERSION)
    elif layout == 'war':
        dependency(dependencies, 'jakarta.servlet', 'jakarta.servlet-api', '6.0.0', 'provided')
        dependency(dependencies, 'org.apache.commons', 'commons-lang3', '3.17.0')
    plugins = ET.SubElement(ET.SubElement(project, 'build'), 'plugins')
    plugin(plugins, 'org.apache.maven.plugins', 'maven-compiler-plugin', '3.13.0')
    plugin(plugins, 'org.apache.maven.plugins', 'maven-jar-plugin', '3.4.2')
    if layout == 'boot':
        boot = plugin(plugins, 'org.springframework.boot', 'spring-boot-maven-plugin', BOOT_VERSION)
        ET.SubElement(ET.SubElement(boot, 'configuration'), 'mainClass').text = 'sample.Main'
        execution = ET.SubElement(ET.SubElement(boot, 'executions'), 'execution')
        ET.SubElement(ET.SubElement(execution, 'goals'), 'goal').text = 'repackage'
    elif layout == 'war':
        war = plugin(plugins, 'org.apache.maven.plugins', 'maven-war-plugin', '3.4.0')
        ET.SubElement(ET.SubElement(war, 'configuration'), 'failOnMissingWebXml').text = 'false'
    return ET.tostring(project, encoding='unicode') + '\n'


def dependency(parent, group, artifact, version, scope=None):
    item = ET.SubElement(parent, 'dependency')
    for name, value in [('groupId', group), ('artifactId', artifact), ('version', version)]:
        ET.SubElement(item, name).text = value
    if scope:
        ET.SubElement(item, 'scope').text = scope


def plugin(parent, group, artifact, version):
    item = ET.SubElement(parent, 'plugin')
    for name, value in [('groupId', group), ('artifactId', artifact), ('version', version)]:
        ET.SubElement(item, name).text = value
    return item


def pricing(discount, fallback):
    return f'''package sample;
public final class Quote {{
    public static int price(boolean eligible) {{
        return eligible && FlagrDecisions.discountEnabled() ? {discount} : {fallback};
    }}
}}
'''


def fixture(root, layout, publication):
    write(root / 'pom.xml', pom(layout, publication))
    write(root / '.gitignore', 'target/\n')
    write(root / 'fold-rules.yml', '''version: 1
folds:
  - match: {CallSite: {callee_class: sample.FlagrDecisions, callee_name: discountEnabled}}
    value: false
''')
    sources = root / 'src/main/java/sample'
    write(sources / 'Quote.java', pricing(9000, 10000))
    write(sources / 'FlagrDecisions.java', '''package sample;
public final class FlagrDecisions { public static boolean discountEnabled() { return true; } }
''')
    if layout == 'boot':
        write(sources / 'Main.java', '''package sample;
public final class Main { public static void main(String[] args) { Quote.price(true); } }
''')
    elif layout == 'war':
        write(sources / 'PriceServlet.java', '''package sample;
public final class PriceServlet extends jakarta.servlet.http.HttpServlet {
    @Override protected void doGet(jakarta.servlet.http.HttpServletRequest request,
            jakarta.servlet.http.HttpServletResponse response) { Quote.price(true); }
}
''')


def commit(root, label, log):
    run(['git', 'add', '.'], root, log)
    run(['git', '-c', 'user.name=GraphFold fixture', '-c', 'user.email=fixture@example.invalid',
         'commit', '--no-verify', '-qm', label], root, log)
    return subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=root, text=True).strip()


def check_archive(root, layout):
    archive = root / 'target' / f'{layout}-1.{"war" if layout == "war" else "jar"}'
    with zipfile.ZipFile(archive) as package:
        names = set(package.namelist())
    prefix = {'library': '', 'boot': 'BOOT-INF/classes/', 'war': 'WEB-INF/classes/'}[layout]
    if prefix + 'sample/Quote.class' not in names:
        raise AssertionError(f'Missing business bytecode in actual {layout} archive')
    if layout == 'boot' and not any(name.startswith(f'BOOT-INF/lib/spring-boot-{BOOT_VERSION}') for name in names):
        raise AssertionError('Boot fixture was not repackaged with its actual dependency')
    if layout == 'war' and 'WEB-INF/lib/commons-lang3-3.17.0.jar' not in names:
        raise AssertionError('WAR fixture lacks its packaged dependency')
    return archive


def check_snapshot(path, layout, sha, source):
    snapshot = json.loads(path.read_text())
    if snapshot['sha'] != sha or len(snapshot['scopes']) != 1:
        raise AssertionError('Snapshot is not bound to the expected revision and one selected archive')
    scope = snapshot['scopes'][0]
    if scope['id'] != f'fixture:{layout}' or scope['javaVersion'] != 17:
        raise AssertionError('Maven model did not export its project identity and actual JDK 17')
    roots = {Path(p).resolve() for p in scope['sourceRoots']}
    outputs = {Path(p).resolve() for p in scope['sourceOutputs']}
    if source.resolve() not in roots or source.resolve() not in outputs:
        raise AssertionError('Maven compile-source association was not exported')


def check_report(path, expected, source_path='src/main/java/sample/Quote.java'):
    result = json.loads(path.read_text())
    if result['verdict'] != expected or result['diagnostics']:
        raise AssertionError(f'{expected} requires a complete static analysis; inspect {path}')
    metadata = result['metadata']
    if metadata['obligations'] <= 0 or (expected == 'PASS' and metadata['obligations'] != metadata['provedObligations']):
        raise AssertionError(f'Golden did not analyze and prove its real change: {path}')
    audits = result['ruleAudits']
    if len(audits) != 2 or any(a['selectionStatus'] != 'SELECTED' or a['matchedCallCount'] != 1 for a in audits):
        raise AssertionError(f'Both actual archive graphs must select the business control: {path}')
    findings = result['findings']
    if (expected == 'FAIL') != bool(findings):
        raise AssertionError(f'Diagnostic-only blocking cannot replace detection: {path}')
    if findings and {location['path'] for item in findings for location in item['locations']} != {source_path}:
        raise AssertionError(f'Uncontrolled changes must map to their Maven-owned source: {path}')
    return dict(verdict=result['verdict'], obligations=metadata['obligations'], proved=metadata['provedObligations'],
                findings=len(findings), audits=len(audits))


def verify_layout(layout, output, executable, common, coordinate, publication):
    root = output / layout / 'repository'
    root.mkdir(parents=True)
    log = output / layout / 'maven.log'
    fixture(root, layout, publication)
    run(['git', 'init', '-q'], root, log)
    base = commit(root, 'baseline price policy', log)
    base_output = output / layout / 'base-snapshot'
    run([str(executable), *common, 'package', coordinate + ':snapshot',
         '-Dgraphfold.snapshotDirectory=' + str(base_output)], root, log)
    check_archive(root, layout)
    check_snapshot(base_output / 'snapshot.json', layout, base, root / 'src/main/java')
    results = {}
    for mode, discount, fallback, expected in [('controlled', 9001, 10000, 'PASS'), ('uncontrolled', 9001, 10001, 'FAIL')]:
        write(root / 'src/main/java/sample/Quote.java', pricing(discount, fallback))
        head = commit(root, mode + ' price policy', log)
        head_output = output / layout / (mode + '-snapshot')
        report = output / layout / (mode + '-report.json')
        run([str(executable), *common, 'package', coordinate + ':check',
             '-Dgraphfold.snapshotDirectory=' + str(head_output), '-Dgraphfold.baseSnapshot=' + str(base_output / 'snapshot.json'),
             '-Dgraphfold.report=' + str(report)], root, log, expected=0 if expected == 'PASS' else 1)
        check_archive(root, layout)
        check_snapshot(head_output / 'snapshot.json', layout, head, root / 'src/main/java')
        results[mode] = check_report(report, expected)
    return results



def reactor_fixture(root, publication):
    # Maven's model reader requires an unprefixed project root after the XML round trip.
    ET.register_namespace('', 'http://maven.apache.org/POM/4.0.0')
    namespace = '{http://maven.apache.org/POM/4.0.0}'
    project = ET.fromstring(pom('library', publication))
    project.find(namespace + 'artifactId').text = 'reactor'
    project.find(namespace + 'packaging').text = 'pom'
    modules = ET.SubElement(project, namespace + 'modules')
    for index, name in enumerate(('one', 'two'), start=1):
        ET.SubElement(modules, namespace + 'module').text = name
        module = root / name
        fixture(module, 'library', publication)
        child = ET.fromstring((module / 'pom.xml').read_text())
        child.find(namespace + 'artifactId').text = name
        write(module / 'pom.xml', ET.tostring(child, encoding='unicode'))
        # Identical class names and distinct policies make a swapped BASE observable to the static proof.
        write(module / 'src/main/java/sample/Quote.java', pricing(index * 10000 - 1000, index * 10000))
    write(root / 'pom.xml', ET.tostring(project, encoding='unicode'))
    write(root / '.gitignore', 'target/\n')
    write(root / 'fold-rules.yml', (root / 'one/fold-rules.yml').read_text())


def check_reactor_snapshots(directory, sha, root):
    expected = {directory / 'modules' / ('fixture%3A' + name) / 'snapshot.json' for name in ('one', 'two')}
    actual = set(directory.rglob('snapshot.json'))
    if actual != expected:
        raise AssertionError(f'Reactor must retain two separate module manifests: {actual}')
    for name in ('one', 'two'):
        path = directory / 'modules' / ('fixture%3A' + name) / 'snapshot.json'
        check_snapshot(path, name, sha, root / name / 'src/main/java')


def verify_reactor(output, executable, common, coordinate, publication):
    directory = output / 'reactor'
    root = directory / 'repository'
    root.mkdir(parents=True)
    log = directory / 'maven.log'
    reactor_fixture(root, publication)
    run(['git', 'init', '-q'], root, log)
    base = commit(root, 'two independent module policies', log)
    base_output = directory / 'base-snapshot'
    run([str(executable), *common, 'package', coordinate + ':snapshot',
         '-Dgraphfold.snapshotDirectory=' + str(base_output)], root, log)
    check_reactor_snapshots(base_output, base, root)
    base_bytes = {path: path.read_bytes() for path in base_output.rglob('*') if path.is_file()}
    results = {}
    for mode in ('controlled', 'uncontrolled'):
        for index, name in enumerate(('one', 'two'), start=1):
            fallback = index * 10000 + (1 if mode == 'uncontrolled' and name == 'two' else 0)
            write(root / name / 'src/main/java/sample/Quote.java', pricing(index * 10000 - 999, fallback))
        head = commit(root, mode + ' reactor policies', log)
        head_output = directory / (mode + '-snapshot')
        reports = directory / (mode + '-reports')
        run([str(executable), *common, 'package', coordinate + ':check',
             '-Dgraphfold.snapshotDirectory=' + str(head_output), '-Dgraphfold.baseSnapshot=' + str(base_output),
             '-Dgraphfold.report=' + str(reports / 'report.json')],
            root, log, expected=0 if mode == 'controlled' else 1)
        check_reactor_snapshots(head_output, head, root)
        results[mode] = {}
        for name in ('one', 'two'):
            archive = root / name / 'target' / (name + '-1.jar')
            with zipfile.ZipFile(archive) as package:
                if 'sample/Quote.class' not in package.namelist():
                    raise AssertionError(f'Missing selected reactor business bytecode: {archive}')
            report = reports / 'modules' / ('fixture%3A' + name) / 'report.json'
            expected = 'FAIL' if mode == 'uncontrolled' and name == 'two' else 'PASS'
            results[mode][name] = check_report(report, expected, name + '/src/main/java/sample/Quote.java')
        expected_reports = {reports / 'modules' / ('fixture%3A' + name) / 'report.json' for name in ('one', 'two')}
        if set(reports.rglob('report.json')) != expected_reports:
            raise AssertionError('Reactor must preserve both module reports under a shared report destination')
        if any(path.read_bytes() != original for path, original in base_bytes.items()):
            raise AssertionError('A reactor check overwrote its BASE snapshot inputs')
    return results


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--publication-repository', type=Path, required=True)
    parser.add_argument('--version', required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--seed-repository', type=Path, help='Optional existing Maven-layout dependency cache; bytes are recorded')
    args = parser.parse_args()
    if not re.fullmatch(r'[A-Za-z0-9_.+-]+', args.version):
        parser.error('version must be an artifact version, not an option or path')
    if os.environ.get('GITHUB_ACTIONS') != 'true' and os.environ.get('GITHUB_ACTOR') != 'johnsonlee':
        parser.error('Local verification requires the authorized GITHUB_ACTOR=johnsonlee identity')
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    publication = args.publication_repository.resolve()
    for artifact in ['gfc', 'engine', 'maven-plugin']:
        if not (publication / GROUP.replace('.', '/') / artifact / args.version / f'{artifact}-{args.version}.pom').is_file():
            parser.error(f'Missing locally published {artifact} POM under {publication}')
    java = Path(os.environ['JAVA_HOME']) / 'bin/java'
    version = subprocess.check_output([str(java), '-version'], stderr=subprocess.STDOUT, text=True)
    if 'version "17.' not in version:
        parser.error('Run the Maven lifecycle fixtures on JDK 17')
    settings = output / 'settings.xml'
    # Credentials remain environment references. Neither token values nor resolved settings are logged.
    write(settings, '''<settings xmlns="http://maven.apache.org/SETTINGS/1.2.0"><servers><server>
<id>graphitePackages</id><username>${env.GITHUB_ACTOR}</username><password>${env.GITHUB_TOKEN}</password>
</server></servers></settings>\n''')
    seed_hashes = {}
    if args.seed_repository is not None:
        seed = args.seed_repository.resolve()
        for path in sorted(seed.rglob('*')):
            if path.is_file():
                relative = path.relative_to(seed)
                target = output / 'maven-cache' / relative
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(path, target)
                seed_hashes[str(relative)] = hashlib.sha256(path.read_bytes()).hexdigest()
    executable = maven(output)
    common = ['--batch-mode', '--no-transfer-progress', '--settings', str(settings),
              '-Dmaven.repo.local=' + str(output / 'maven-cache'), '-Dstyle.color=never']
    coordinate = f'{GROUP}:maven-plugin:{args.version}'
    results = {layout: verify_layout(layout, output, executable, common, coordinate, publication.as_uri()) for layout in LAYOUTS}
    summary = dict(mavenVersion=MAVEN_VERSION, javaVersion=version.strip(), pluginVersion=args.version,
                   fixtureResults=results, reactorResults=verify_reactor(output, executable, common, coordinate, publication.as_uri()),
                   seededDependencyHashes=seed_hashes, criterion='Static control coverage; no application or container execution')
    write(output / 'summary.json', json.dumps(summary, indent=2) + '\n')
    print(json.dumps(summary, indent=2))


if __name__ == '__main__':
    main()
