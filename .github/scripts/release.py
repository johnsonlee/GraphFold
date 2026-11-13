"""Release validation and local publication verification; no remote mutations."""
import argparse
import hashlib
import json
import re
from pathlib import Path
import xml.etree.ElementTree as ET
import zipfile

SEMVER = re.compile(r"v(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-((?:0|[1-9]\d*|\d*[A-Za-z-][0-9A-Za-z-]*)(?:\.(?:0|[1-9]\d*|\d*[A-Za-z-][0-9A-Za-z-]*))*))?(?:\+([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?")
COMPILER_PLUGIN_SERVICES = {
    "com.sun.source.util.Plugin": "io.johnsonlee.graphfold.compiler.javac.GraphFoldOrigins",
    "org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar":
        "io.johnsonlee.graphfold.compiler.kotlin.KotlinOriginRegistrar",
    "org.jetbrains.kotlin.compiler.plugin.CommandLineProcessor":
        "io.johnsonlee.graphfold.compiler.kotlin.KotlinOriginCommandLineProcessor",
}


def verify_compiler_plugin(archive):
    for service, provider in COMPILER_PLUGIN_SERVICES.items():
        descriptor = f"META-INF/services/{service}"
        implementation = provider.replace(".", "/") + ".class"
        if descriptor not in archive.namelist() or implementation not in archive.namelist():
            raise ValueError(f"Missing compiler plugin service or implementation: {service}")
        providers = [line.split("#", 1)[0].strip() for line in archive.read(descriptor).decode("utf-8").splitlines()]
        if [line for line in providers if line] != [provider]:
            raise ValueError(f"Incorrect compiler plugin service provider: {service}")
    if "io/johnsonlee/graphfold/compiler/protocol/OriginProtocol.class" not in archive.namelist():
        raise ValueError("Missing compiler plugin origin protocol")


PROJECT_DEPENDENCIES = {
    "engine": {"gfc"}, "gfc": set(),
    "gradle-plugin": {"engine"}, "maven-plugin": {"engine"},
}


def verify_project_dependencies(pom, module, release_version, namespace):
    dependencies = [dependency for dependency in pom.findall("m:dependencies/m:dependency", namespace)
                    if dependency.findtext("m:groupId", namespaces=namespace) == "io.johnsonlee.graphfold"]
    names = [dependency.findtext("m:artifactId", namespaces=namespace) for dependency in dependencies]
    if len(names) != len(set(names)) or set(names) != PROJECT_DEPENDENCIES[module]:
        raise ValueError(f"Incorrect {module} project dependency graph: {names}")
    for dependency in dependencies:
        if dependency.findtext("m:version", namespaces=namespace) != release_version:
            raise ValueError(f"{module} dependency differs from release version")
        if dependency.findtext("m:scope", default="compile", namespaces=namespace) not in ("compile", "runtime"):
            raise ValueError(f"{module} project dependency is not a runtime dependency")
        if dependency.findtext("m:optional", default="false", namespaces=namespace) != "false":
            raise ValueError(f"{module} project dependency must not be optional")


def verify_module_metadata(directory, module, release_version):
    path = directory / f"{module}-{release_version}.module"
    if not path.is_file():
        raise ValueError(f"Missing Gradle module metadata: {path}")
    metadata = json.loads(path.read_text())
    expected = {"group": "io.johnsonlee.graphfold", "module": module, "version": release_version}
    if any(metadata.get("component", {}).get(key) != value for key, value in expected.items()):
        raise ValueError(f"Incorrect Gradle module identity: {module}")
    artifacts = [artifact for variant in metadata.get("variants", []) for artifact in variant.get("files", [])]
    if not artifacts:
        raise ValueError(f"Gradle module metadata has no artifacts: {module}")
    for artifact in artifacts:
        name, url = artifact.get("name"), artifact.get("url")
        if not isinstance(name, str) or name != url or Path(name).name != name or not (directory / name).is_file():
            raise ValueError(f"Invalid Gradle module artifact: {module}: {artifact}")


def version(tag):
    match = SEMVER.fullmatch(tag)
    if not match:
        raise ValueError(f"Not a v-prefixed strict SemVer tag: {tag!r}")
    return tag[1:], bool(match.group(4))


def verify(repository, release_version):
    for obsolete in ("adapters", "inputs", "cli"):
        if (repository / "io/johnsonlee/graphfold" / obsolete).exists():
            raise ValueError(f"Obsolete module must not be published: {obsolete}")
    namespace = {"m": "http://maven.apache.org/POM/4.0.0"}
    for module in PROJECT_DEPENDENCIES:
        directory = repository / "io/johnsonlee/graphfold" / module / release_version
        stem = f"{module}-{release_version}"
        for suffix in (".pom", ".jar", "-sources.jar", "-javadoc.jar"):
            artifact = directory / (stem + suffix)
            if not artifact.is_file() or not artifact.stat().st_size:
                raise ValueError(f"Missing or empty publication: {artifact}")
        verify_module_metadata(directory, module, release_version)
        entry = {"gfc": "META-INF/services/com.sun.source.util.Plugin",
                 "engine": "io/johnsonlee/graphfold/engine/GraphFoldEngine.class",
                 "maven-plugin": "META-INF/maven/plugin.xml",
                 "gradle-plugin": "META-INF/gradle-plugins/io.johnsonlee.graphfold.properties"}[module]
        with zipfile.ZipFile(directory / (stem + ".jar")) as archive:
            if entry not in archive.namelist():
                raise ValueError(f"Missing implementation entry {entry}")
            if module == "gfc":
                verify_compiler_plugin(archive)
            if module == "engine" and "engine/origin/compiler-plugin.jar" in archive.namelist():
                raise ValueError("Engine still embeds the compiler plugin")
            if module == "engine":
                for required in ("engine/inputs/StaticInputSnapshots.class", "engine/inputs/archive/ArchiveInput.class"):
                    if f"io/johnsonlee/graphfold/{required}" not in archive.namelist():
                        raise ValueError(f"Missing engine input implementation: {required}")
                if any(name.startswith(("io/johnsonlee/graphfold/gradle/", "io/johnsonlee/graphfold/maven/",
                                        "io/johnsonlee/graphfold/engine/build/")) for name in archive.namelist()):
                    raise ValueError("Engine must not contain build-tool integrations")
            if module in ("gradle-plugin", "maven-plugin"):
                if any(name.startswith(("io/johnsonlee/graphfold/engine/inputs/",
                                        "io/johnsonlee/graphfold/integration/")) for name in archive.namelist()):
                    raise ValueError("Plugins must not duplicate engine input implementations")
            if module == "maven-plugin":
                descriptor = ET.fromstring(archive.read("META-INF/maven/plugin.xml"))
                if descriptor.findtext("version") != release_version:
                    raise ValueError("Maven plugin descriptor differs from release version")
                if {mojo.findtext("goal") for mojo in descriptor.findall("mojos/mojo")} != {"snapshot", "check"}:
                    raise ValueError("Maven plugin descriptor must expose snapshot and check goals")
                for mojo in descriptor.findall("mojos/mojo"):
                    implementation = mojo.findtext("implementation", "").replace(".", "/") + ".class"
                    if implementation not in archive.namelist():
                        raise ValueError("Missing Maven goal implementation")
        pom = ET.parse(directory / (stem + ".pom"))
        verify_project_dependencies(pom, module, release_version, namespace)
        if module == "maven-plugin" and pom.findtext("m:packaging", namespaces=namespace) != "maven-plugin":
            raise ValueError("Maven plugin publication must use maven-plugin packaging")
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
    args = parser.parse_args()
    if args.command == "version":
        parsed, prerelease = version(args.tag)
        print(f"version={parsed}\nprerelease={str(prerelease).lower()}")
    elif args.command == "verify":
        verify(args.repository, args.version)
    elif args.command == "compare":
        compare(args.expected, args.actual)
    else:
        checksums(args.directory)
