#!/usr/bin/env python3
"""Export only the isolated UAST experiment; never modify implementation files."""
import argparse
import difflib
import hashlib
import json
from pathlib import Path

ENGINE = "engine/src/main/kotlin/io/johnsonlee/graphfold/engine"
TEST = "engine/src/test/kotlin/io/johnsonlee/graphfold/engine"
FILES = (
    "build.gradle.kts",
    "engine/build.gradle.kts",
    f"{ENGINE}/build/KotlinBuildAdapter.kt",
    f"{ENGINE}/source/AstClassifier.kt",
    f"{ENGINE}/source/ChangeClassifier.kt",
    f"{ENGINE}/source/JavaSourceIndex.kt",
    f"{ENGINE}/source/KotlinSourceIndex.kt",
    f"{ENGINE}/source/UastSession.kt",
    f"{TEST}/graphite/KotlinMetadataShapeTest.kt",
    f"{TEST}/source/JavaSourceIndexTest.kt",
    f"{TEST}/source/KotlinSourceIndexTest.kt",
    f"{TEST}/source/UastSessionTest.kt",
)


def digest(content):
    return hashlib.sha256(content).hexdigest() if content is not None else None


def unified(old, new, path):
    before = [] if old is None else old.decode("utf-8").splitlines(keepends=True)
    after = new.decode("utf-8").splitlines(keepends=True)
    lines = difflib.unified_diff(before, after, fromfile="/dev/null" if old is None else f"a/{path}",
                                 tofile=f"b/{path}")
    result = [f"diff --git a/{path} b/{path}\n"]
    if old is None:
        result.append("new file mode 100644\n")
    for line in lines:
        result.append(line)
        if not line.endswith("\n"):
            result.append("\n\\ No newline at end of file\n")
    return "".join(result)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--candidate", required=True, type=Path, help="Isolated UAST candidate checkout")
    parser.add_argument("--base", type=Path, default=Path(__file__).resolve().parents[3],
                        help="Baseline source snapshot (defaults to this repository)")
    parser.add_argument("--output", type=Path, default=Path(__file__).with_name("uast-integration.patch"))
    args = parser.parse_args()
    base, candidate = args.base.resolve(), args.candidate.resolve()
    if base == candidate or not (candidate / "engine/build.gradle.kts").is_file():
        parser.error("--candidate must be a separate GraphFold source tree")
    patches, manifest = [], []
    for relative in FILES:
        original, changed = base / relative, candidate / relative
        if not changed.is_file():
            parser.error(f"Candidate file missing: {relative}; sync the candidate before exporting")
        old = original.read_bytes() if original.exists() else None
        new = changed.read_bytes()
        if old != new:
            patches.append(unified(old, new, relative))
            manifest.append({"path": relative, "baseSha256": digest(old), "candidateSha256": digest(new)})
    if not patches:
        parser.error("No UAST-specific differences found")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text("".join(patches), encoding="utf-8")
    metadata = args.output.with_suffix(".manifest.json")
    metadata.write_text(json.dumps({"format": 1, "files": manifest}, indent=2) + "\n", encoding="utf-8")
    print(f"Exported {len(manifest)} files to {args.output}; source hashes: {metadata}")


if __name__ == "__main__":
    main()
