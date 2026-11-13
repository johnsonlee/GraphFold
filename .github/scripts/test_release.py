import unittest
import json
import zipfile
import shutil
from pathlib import Path
from tempfile import TemporaryDirectory
from release import version, checksums, verify, compare, COMPILER_PLUGIN_SERVICES


class VersionTest(unittest.TestCase):
    def test_release(self):
        self.assertEqual(("1.2.3", False), version("v1.2.3"))

    def test_prerelease(self):
        self.assertEqual(("1.2.3-rc.1+build.12", True), version("v1.2.3-rc.1+build.12"))

    def test_invalid(self):
        for tag in ("1.2.3", "v1.2", "v01.2.3", "v1.2.3-01", "v1.2.3-", "v1.2.3\n", "v1.2.3;echo bad"):
            with self.subTest(tag=tag), self.assertRaises(ValueError):
                version(tag)


class AssetsTest(unittest.TestCase):
    def test_checksums(self):
        with TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "asset.zip").write_bytes(b"artifact")
            checksums(root)
            first = (root / "SHA256SUMS").read_text()
            self.assertIn("  asset.zip\n", first)
            checksums(root)
            self.assertEqual(first, (root / "SHA256SUMS").read_text())


    def test_verified_publication_comparison(self):
        with TemporaryDirectory() as temporary:
            root = Path(temporary)
            expected, actual = root / "expected", root / "actual"
            expected.mkdir()
            actual.mkdir()
            with self.assertRaises(ValueError):
                compare(expected, actual)
            for name in ("engine.jar", "engine-sources.jar", "engine.pom", "engine.module"):
                (expected / name).write_bytes(name.encode())
                (actual / name).write_bytes(name.encode())
            (actual / "engine.jar.asc").write_bytes(b"signature")
            compare(expected, actual)
            (actual / "engine.jar").write_bytes(b"unverified")
            with self.assertRaisesRegex(ValueError, "differs.*engine.jar"):
                compare(expected, actual)
            (actual / "engine.jar").unlink()
            with self.assertRaisesRegex(ValueError, "inventory"):
                compare(expected, actual)

    def test_empty_assets_fail(self):
        with TemporaryDirectory() as temporary, self.assertRaises(ValueError):
            checksums(Path(temporary))

    def test_missing_publication_fails(self):
        with TemporaryDirectory() as temporary, self.assertRaises(ValueError):
            verify(Path(temporary), "1.0.0")


class PublicationsTest(unittest.TestCase):
    VERSION = "1.2.3"
    GROUP = "io.johnsonlee.graphfold"

    def setUp(self):
        temporary = TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.repository = self.root / "repository"
        self.plugin_entries = {
            "io/johnsonlee/graphfold/compiler/protocol/OriginProtocol.class": b"protocol",
        }
        for service, provider in COMPILER_PLUGIN_SERVICES.items():
            self.plugin_entries[f"META-INF/services/{service}"] = (provider + "\n").encode()
            self.plugin_entries[provider.replace(".", "/") + ".class"] = b"implementation"
        self.engine_entries = {
            "io/johnsonlee/graphfold/engine/GraphFoldEngine.class": b"engine",
            "io/johnsonlee/graphfold/engine/inputs/StaticInputSnapshots.class": b"snapshot",
            "io/johnsonlee/graphfold/engine/inputs/archive/ArchiveInput.class": b"archive",
        }
        entries = {
            "gfc": self.plugin_entries,
            "engine": self.engine_entries,
            "gradle-plugin": {"META-INF/gradle-plugins/io.johnsonlee.graphfold.properties": b"plugin"},
            "maven-plugin": self.maven_entries(),
        }
        for module, contents in entries.items():
            directory = self.directory(module)
            directory.mkdir(parents=True)
            self.write_jar(module, contents)
            metadata = {"component": {"group": self.GROUP, "module": module, "version": self.VERSION},
                        "variants": [{"name": "runtimeElements", "files": [{
                            "name": f"{module}-{self.VERSION}.jar", "url": f"{module}-{self.VERSION}.jar"}]}]}
            (directory / f"{module}-{self.VERSION}.module").write_text(json.dumps(metadata))
            for classifier in ("sources", "javadoc"):
                with zipfile.ZipFile(directory / f"{module}-{self.VERSION}-{classifier}.jar", "w") as archive:
                    archive.writestr("entry", "fixture")
            graph = {"engine": ("gfc",), "gfc": (),
                     "gradle-plugin": ("engine",), "maven-plugin": ("engine",)}
            dependency = "".join(self.dependency(name) for name in graph[module])
            self.write_pom(module, dependency)
        marker = "io.johnsonlee.graphfold.gradle.plugin"
        self.directory(marker).mkdir(parents=True)
        self.write_pom(marker, self.dependency("gradle-plugin"))

    def directory(self, module):
        return self.repository / "io/johnsonlee/graphfold" / module / self.VERSION

    def dependency(self, module, version=None, extra=""):
        return (f"<dependency><groupId>{self.GROUP}</groupId><artifactId>{module}</artifactId>"
                f"<version>{version or self.VERSION}</version>{extra}</dependency>")

    def write_pom(self, module, dependencies):
        (self.directory(module) / f"{module}-{self.VERSION}.pom").write_text(
            '<project xmlns="http://maven.apache.org/POM/4.0.0">'
            f"<groupId>{self.GROUP}</groupId><artifactId>{module}</artifactId><version>{self.VERSION}</version>"
            f"<packaging>{'maven-plugin' if module == 'maven-plugin' else 'jar'}</packaging>"
            f"<dependencies>{dependencies}</dependencies></project>")

    def write_jar(self, module, entries):
        with zipfile.ZipFile(self.directory(module) / f"{module}-{self.VERSION}.jar", "w") as archive:
            for name, content in entries.items():
                archive.writestr(name, content)

    def test_synchronized_publications_pass(self):
        verify(self.repository, self.VERSION)

    def test_relocated_module_metadata_keeps_published_identity_and_artifacts(self):
        for module, wrong_name in (("gradle-plugin", "gradle"), ("maven-plugin", "maven")):
            path = self.directory(module) / f"{module}-{self.VERSION}.module"
            original = path.read_text()
            metadata = json.loads(original)
            metadata["component"]["module"] = wrong_name
            path.write_text(json.dumps(metadata))
            with self.assertRaisesRegex(ValueError, "module identity"):
                verify(self.repository, self.VERSION)
            metadata = json.loads(original)
            metadata["variants"][0]["files"][0]["url"] = f"{wrong_name}-{self.VERSION}.jar"
            path.write_text(json.dumps(metadata))
            with self.assertRaisesRegex(ValueError, "module artifact"):
                verify(self.repository, self.VERSION)
            path.unlink()
            with self.assertRaisesRegex(ValueError, "Missing Gradle module"):
                verify(self.repository, self.VERSION)
            path.write_text(original)

    def maven_entries(self, version=None, goals=("snapshot", "check")):
        entries = {}
        mojos = ""
        for goal in goals:
            implementation = f"io.johnsonlee.graphfold.maven.GraphFold{goal.title()}Mojo"
            entries[implementation.replace(".", "/") + ".class"] = b"mojo"
            mojos += f"<mojo><goal>{goal}</goal><implementation>{implementation}</implementation></mojo>"
        entries["META-INF/maven/plugin.xml"] = (
            f"<plugin><version>{version or self.VERSION}</version><mojos>{mojos}</mojos></plugin>").encode()
        return entries

    def test_maven_plugin_descriptor_and_goals_must_be_published(self):
        for contents in (self.maven_entries(version="1.2.2"), self.maven_entries(goals=("snapshot",)),
                         {"META-INF/maven/plugin.xml": self.maven_entries()["META-INF/maven/plugin.xml"]}):
            with self.subTest(contents=contents):
                self.write_jar("maven-plugin", contents)
                with self.assertRaisesRegex(ValueError, "Maven"):
                    verify(self.repository, self.VERSION)

    def test_maven_plugin_packaging_is_required(self):
        pom = self.directory("maven-plugin") / f"maven-plugin-{self.VERSION}.pom"
        pom.write_text(pom.read_text().replace("<packaging>maven-plugin</packaging>", "<packaging>jar</packaging>"))
        with self.assertRaisesRegex(ValueError, "packaging"):
            verify(self.repository, self.VERSION)

    def test_build_tool_integrations_cannot_leak_into_engine_publication(self):
        for prefix in ("gradle", "maven", "engine/build"):
            with self.subTest(prefix=prefix):
                self.write_jar("engine", {**self.engine_entries,
                    f"io/johnsonlee/graphfold/{prefix}/ArchiveInput.class": b"adapter",
                })
                with self.assertRaisesRegex(ValueError, "Engine must not contain"):
                    verify(self.repository, self.VERSION)

    def test_engine_owns_packaged_input_implementation(self):
        for entry in list(self.engine_entries)[1:]:
            with self.subTest(entry=entry):
                self.write_jar("engine", {name: value for name, value in self.engine_entries.items() if name != entry})
                with self.assertRaisesRegex(ValueError, "Missing engine input"):
                    verify(self.repository, self.VERSION)

    def test_plugins_cannot_duplicate_input_implementation(self):
        for module in ("gradle-plugin", "maven-plugin"):
            entries = (self.maven_entries() if module == "maven-plugin" else
                       {"META-INF/gradle-plugins/io.johnsonlee.graphfold.properties": b"plugin"})
            for prefix in ("engine/inputs", "integration"):
                with self.subTest(module=module, prefix=prefix):
                    self.write_jar(module, {**entries, f"io/johnsonlee/graphfold/{prefix}/ArchiveInput.class": b"copy"})
                    with self.assertRaisesRegex(ValueError, "Plugins must not duplicate"):
                        verify(self.repository, self.VERSION)
            self.write_jar(module, entries)

    def test_removed_modules_cannot_leak_into_release_repository(self):
        for module in ("adapters", "inputs", "cli"):
            with self.subTest(module=module):
                self.directory(module).mkdir(parents=True)
                with self.assertRaisesRegex(ValueError, "Obsolete module"):
                    verify(self.repository, self.VERSION)
                shutil.rmtree(self.directory(module).parent)

    def test_missing_compiler_publication_fails(self):
        shutil.rmtree(self.directory("gfc"))
        with self.assertRaisesRegex(ValueError, "Missing or empty publication"):
            verify(self.repository, self.VERSION)

    def test_invalid_entrypoint_dependency_fails(self):
        dependencies = (
            "", self.dependency("engine", "1.2.2"),
            self.dependency("engine", extra="<scope>provided</scope>"),
            self.dependency("engine", extra="<optional>true</optional>"),
            self.dependency("engine") * 2,
        )
        for dependency in dependencies:
            with self.subTest(dependency=dependency):
                self.write_pom("gradle-plugin", dependency)
                with self.assertRaisesRegex(ValueError, "gradle-plugin.*dependency"):
                    verify(self.repository, self.VERSION)

    def test_compiler_provider_has_no_runtime_engine_dependency(self):
        self.write_pom("gfc", self.dependency("engine"))
        with self.assertRaisesRegex(ValueError, "Incorrect gfc project dependency graph"):
            verify(self.repository, self.VERSION)

    def test_core_cannot_depend_on_build_adapter(self):
        self.write_pom("engine", self.dependency("gradle-plugin"))
        with self.assertRaisesRegex(ValueError, "Incorrect engine project dependency graph"):
            verify(self.repository, self.VERSION)

    def test_entrypoints_cannot_depend_on_each_other(self):
        self.write_pom("maven-plugin", self.dependency("gradle-plugin"))
        with self.assertRaisesRegex(ValueError, "Incorrect maven-plugin project dependency graph"):
            verify(self.repository, self.VERSION)

    def test_missing_maven_plugin_publication_fails(self):
        shutil.rmtree(self.directory("maven-plugin"))
        with self.assertRaisesRegex(ValueError, "Missing or empty publication"):
            verify(self.repository, self.VERSION)

    def test_embedded_plugin_fails(self):
        self.write_jar("engine", {"io/johnsonlee/graphfold/engine/GraphFoldEngine.class": b"engine",
                                  "engine/origin/compiler-plugin.jar": b"obsolete"})
        with self.assertRaisesRegex(ValueError, "Engine still embeds"):
            verify(self.repository, self.VERSION)

    def test_missing_compiler_spi_or_protocol_fails(self):
        for entry in self.plugin_entries:
            with self.subTest(entry=entry):
                entries = dict(self.plugin_entries)
                del entries[entry]
                self.write_jar("gfc", entries)
                with self.assertRaisesRegex(ValueError, "Missing"):
                    verify(self.repository, self.VERSION)

    def test_wrong_compiler_spi_provider_fails(self):
        for service in COMPILER_PLUGIN_SERVICES:
            with self.subTest(service=service):
                entries = dict(self.plugin_entries)
                entries[f"META-INF/services/{service}"] = b"wrong.Provider\n"
                self.write_jar("gfc", entries)
                with self.assertRaisesRegex(ValueError, "Incorrect compiler plugin service provider"):
                    verify(self.repository, self.VERSION)


if __name__ == "__main__":
    unittest.main()
