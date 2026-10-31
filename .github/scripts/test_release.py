import unittest
from pathlib import Path
from tempfile import TemporaryDirectory
from release import version, checksums, verify, compare


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


if __name__ == "__main__":
    unittest.main()
