import unittest
import io
import zipfile
import shutil
import tarfile
from types import SimpleNamespace
from unittest.mock import patch
from pathlib import Path
from tempfile import TemporaryDirectory
from release import (version, checksums, verify, compare, verify_cli_version, smoke,
                     CLI_VERSION_RESOURCE, COMPILER_PLUGIN_SERVICES)


class VersionTest(unittest.TestCase):
    def test_packaged_version_matches_release(self):
        with zipfile.ZipFile(io.BytesIO(), "w") as archive:
            archive.writestr(CLI_VERSION_RESOURCE, "1.2.3\n")
            verify_cli_version(archive, "1.2.3")
            with self.assertRaisesRegex(ValueError, "differs"):
                verify_cli_version(archive, "1.2.4")

    def test_missing_packaged_version_fails(self):
        with zipfile.ZipFile(io.BytesIO(), "w") as archive:
            with self.assertRaisesRegex(ValueError, "Missing packaged CLI version"):
                verify_cli_version(archive, "1.2.3")

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
        entries = {
            "gfc": self.plugin_entries,
            "engine": {"io/johnsonlee/graphfold/engine/GraphFoldEngine.class": b"engine"},
            "cli": {"io/johnsonlee/graphfold/cli/MainKt.class": b"cli",
                    CLI_VERSION_RESOURCE: self.VERSION.encode()},
            "gradle-plugin": {"META-INF/gradle-plugins/io.johnsonlee.graphfold.properties": b"plugin"},
        }
        for module, contents in entries.items():
            directory = self.directory(module)
            directory.mkdir(parents=True)
            self.write_jar(module, contents)
            for classifier in ("sources", "javadoc"):
                with zipfile.ZipFile(directory / f"{module}-{self.VERSION}-{classifier}.jar", "w") as archive:
                    archive.writestr("entry", "fixture")
            dependency = self.dependency("gfc") if module == "engine" else ""
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
            f"<dependencies>{dependencies}</dependencies></project>")

    def write_jar(self, module, entries):
        with zipfile.ZipFile(self.directory(module) / f"{module}-{self.VERSION}.jar", "w") as archive:
            for name, content in entries.items():
                archive.writestr(name, content)

    def test_synchronized_publications_pass(self):
        verify(self.repository, self.VERSION)

    def test_missing_compiler_publication_fails(self):
        shutil.rmtree(self.directory("gfc"))
        with self.assertRaisesRegex(ValueError, "Missing or empty publication"):
            verify(self.repository, self.VERSION)

    def test_invalid_engine_dependency_fails(self):
        dependencies = (
            "", self.dependency("gfc", "1.2.2"),
            self.dependency("gfc", extra="<scope>provided</scope>"),
            self.dependency("gfc", extra="<optional>true</optional>"),
            self.dependency("gfc") * 2,
        )
        for dependency in dependencies:
            with self.subTest(dependency=dependency):
                self.write_pom("engine", dependency)
                with self.assertRaisesRegex(ValueError, "Engine.*compiler plugin"):
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

    def distributions(self, plugin_versions):
        installation = self.root / f"graphfold-{self.VERSION}"
        if installation.exists():
            shutil.rmtree(installation)
        library = installation / "lib"
        library.mkdir(parents=True)
        for plugin_version in plugin_versions:
            shutil.copyfile(self.directory("gfc") / f"gfc-{self.VERSION}.jar",
                            library / f"gfc-{plugin_version}.jar")
        distributions = self.root / "distributions"
        distributions.mkdir(exist_ok=True)
        with zipfile.ZipFile(distributions / f"graphfold-{self.VERSION}.zip", "w") as archive:
            for entry in library.iterdir():
                archive.write(entry, entry.relative_to(self.root))
        with tarfile.open(distributions / f"graphfold-{self.VERSION}.tar", "w") as archive:
            archive.add(installation, arcname=installation.name)
        return distributions

    def test_distributions_include_synchronized_plugin(self):
        results = [SimpleNamespace(returncode=0, stdout="--fold", stderr=""),
                   SimpleNamespace(returncode=0, stdout=f"graphfold version {self.VERSION}", stderr=""),
                   SimpleNamespace(returncode=2)] * 2
        with patch("release.subprocess.run", side_effect=results) as run:
            smoke(self.distributions([self.VERSION]), self.VERSION)
        self.assertEqual(6, run.call_count)

    def test_missing_mismatched_or_duplicate_distribution_plugin_fails(self):
        for versions in ([], ["1.2.2"], [self.VERSION, "1.2.2"]):
            with self.subTest(versions=versions), self.assertRaisesRegex(ValueError, "exactly the released compiler plugin"):
                smoke(self.distributions(versions), self.VERSION)


if __name__ == "__main__":
    unittest.main()
