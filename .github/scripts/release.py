"""Release validation and local publication verification; no remote mutations."""
import argparse
import hashlib
import re
from pathlib import Path
import xml.etree.ElementTree as ET
import zipfile
import subprocess
import tarfile
import tempfile

SEMVER = re.compile(r"v(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-((?:0|[1-9]\d*|\d*[A-Za-z-][0-9A-Za-z-]*)(?:\.(?:0|[1-9]\d*|\d*[A-Za-z-][0-9A-Za-z-]*))*))?(?:\+([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?")


def version(tag):
    match = SEMVER.fullmatch(tag)
    if not match:
        raise ValueError(f"Not a v-prefixed strict SemVer tag: {tag!r}")
    return tag[1:], bool(match.group(4))


def verify(repository, release_version):
    namespace = {"m": "http://maven.apache.org/POM/4.0.0"}
    for module in ("engine", "cli", "gradle-plugin"):
        directory = repository / "io/johnsonlee/graphfold" / module / release_version
        stem = f"{module}-{release_version}"
        for suffix in (".pom", ".jar", "-sources.jar", "-javadoc.jar"):
            artifact = directory / (stem + suffix)
            if not artifact.is_file() or not artifact.stat().st_size:
                raise ValueError(f"Missing or empty publication: {artifact}")
        entry = {"engine": "io/johnsonlee/graphfold/engine/GraphFoldEngine.class",
                 "cli": "io/johnsonlee/graphfold/cli/MainKt.class",
                 "gradle-plugin": "META-INF/gradle-plugins/io.johnsonlee.graphfold.properties"}[module]
        with zipfile.ZipFile(directory / (stem + ".jar")) as archive:
            if entry not in archive.namelist():
                raise ValueError(f"Missing implementation entry {entry}")
        pom = ET.parse(directory / (stem + ".pom"))
        for key, expected in (("groupId", "io.johnsonlee.graphfold"), ("artifactId", module), ("version", release_version)):
            if pom.findtext(f"m:{key}", namespaces=namespace) != expected:
                raise ValueError(f"Incorrect POM {key}: {directory}")
    marker = repository / "io/johnsonlee/graphfold/io.johnsonlee.graphfold.gradle.plugin" / release_version / f"io.johnsonlee.graphfold.gradle.plugin-{release_version}.pom"
    if not marker.is_file():
        raise ValueError(f"Missing plugin marker: {marker}")
    marker_pom = ET.parse(marker)
    dependency = marker_pom.find("m:dependencies/m:dependency", namespace)
    expected = {"groupId": "io.johnsonlee.graphfold", "artifactId": "gradle-plugin", "version": release_version}
    if dependency is None or any(dependency.findtext(f"m:{key}", namespaces=namespace) != value
                                 for key, value in expected.items()):
        raise ValueError("Plugin marker does not reference the released implementation")


def compare(expected, actual):
    """Compare publication payloads; signatures/checksums/metadata are transport sidecars."""
    def payloads(root):
        return {path.relative_to(root): path for path in root.rglob("*")
                if path.is_file() and path.suffix in (".jar", ".pom", ".module")}
    original, rebuilt = payloads(expected), payloads(actual)
    if not original or original.keys() != rebuilt.keys():
        raise ValueError("Publication payload inventory differs from verified release")
    for name, path in original.items():
        if hashlib.sha256(path.read_bytes()).digest() != hashlib.sha256(rebuilt[name].read_bytes()).digest():
            raise ValueError(f"Publication differs from verified release: {name}")


def checksums(directory):
    assets = sorted(path for path in directory.iterdir() if path.is_file() and path.name != "SHA256SUMS")
    if not assets:
        raise ValueError("No release assets")
    output = "".join(f"{hashlib.sha256(path.read_bytes()).hexdigest()}  {path.name}\n" for path in assets)
    (directory / "SHA256SUMS").write_text(output)


def smoke(distributions, release_version):
    for extension in ("zip", "tar"):
        archive = distributions / f"graphfold-{release_version}.{extension}"
        with tempfile.TemporaryDirectory() as temporary:
            if extension == "zip":
                with zipfile.ZipFile(archive) as stream:
                    stream.extractall(temporary)
            else:
                with tarfile.open(archive) as stream:
                    stream.extractall(temporary, filter="data")
            executable = Path(temporary) / f"graphfold-{release_version}/bin/graphfold"
            help_result = subprocess.run(["bash", str(executable), "--help"], capture_output=True, text=True, timeout=60)
            if help_result.returncode != 0 or "--fold" not in help_result.stdout:
                raise ValueError(f"Packaged CLI failed help smoke: {help_result.stderr}")
            invalid = subprocess.run(["bash", str(executable)], capture_output=True, text=True, timeout=60)
            if invalid.returncode != 2:
                raise ValueError(f"Packaged CLI failed input-error exit contract: {invalid.returncode}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("version").add_argument("tag")
    publication = commands.add_parser("verify")
    publication.add_argument("repository", type=Path)
    publication.add_argument("version")
    comparison = commands.add_parser("compare")
    comparison.add_argument("expected", type=Path)
    comparison.add_argument("actual", type=Path)
    commands.add_parser("checksums").add_argument("directory", type=Path)
    distribution = commands.add_parser("smoke")
    distribution.add_argument("directory", type=Path)
    distribution.add_argument("version")
    args = parser.parse_args()
    if args.command == "version":
        parsed, prerelease = version(args.tag)
        print(f"version={parsed}\nprerelease={str(prerelease).lower()}")
    elif args.command == "verify":
        verify(args.repository, args.version)
    elif args.command == "compare":
        compare(args.expected, args.actual)
    elif args.command == "smoke":
        smoke(args.directory, args.version)
    else:
        checksums(args.directory)
