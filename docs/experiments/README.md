# Isolated UAST integration candidate

`uast-integration.patch` preserves the source-only UAST experiment without replacing the main implementation. It adds the Lint/UAST runtime, shared Java/Kotlin classification, source adapters, and affected tests. Dependencies remain Maven coordinates; no downloaded binaries or build outputs are included.

Regenerate after completing and testing the candidate:

```sh
python3 docs/experiments/export-uast-candidate.py \
  --candidate /path/to/isolated/GraphFold-uast \
  --output docs/experiments/uast-integration.patch
```

The exporter compares an explicit UAST file allowlist against this repository. Use `--base /path/to/baseline` to select another snapshot. `uast-integration.manifest.json` records SHA-256 hashes of every changed baseline/candidate file; a null baseline hash means a new file. Export is deterministic for identical inputs.

Apply only in an isolated checkout containing the matching baseline source snapshot:

```sh
git worktree add --detach ../GraphFold-uast-evaluation <baseline-commit>
cd ../GraphFold-uast-evaluation
git apply --check /absolute/path/to/docs/experiments/uast-integration.patch
git apply /absolute/path/to/docs/experiments/uast-integration.patch
./gradlew check
```

The baseline commit must include the current shared implementation and regression fixtures. A patch exported against uncommitted files is tied to that working snapshot, not automatically to HEAD. Preserve that snapshot before attempting reproduction; never force-apply a failed check. Compare baseline hashes in the manifest when diagnosing mismatches.

Before final export, synchronize non-UAST changes into the candidate, especially new generated-code source-location fixtures/tests and coverage regression tests. They belong to the common baseline and are intentionally not duplicated in the UAST patch. The allowlist excludes unrelated implementation/test drift; it must be updated if the experiment adds another necessary integration file.

An exported patch is a reproducibility artifact, not evidence that tests passed. Consult the UAST evaluation document for the exact validated scope, failures, coverage, and remaining limitations.
