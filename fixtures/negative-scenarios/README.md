# Negative folding scenarios with an enabled-path check

`NegativeScenarioIntegrationTest` loads six checked-in full-tree fixtures, each with
base/head sources, `fold.yml` and `active.yml`. Java and Kotlin each cover:

| Scenario | Negative contract | Enabled-path check |
| --- | --- | --- |
| `multiple_ids` | Two IDs independently disabled; common arithmetic remains | Enable only the second ID; its change must be detected |
| `control_groups` | Enumerate A=0 and NA=-1 as separate control assumptions; both retain baseline behavior | Group=1 exposes the changed treatment expression |
| `mixed_keys` | Boolean AB key and numeric release-version key combine; ordinary execution remains | Keep AB disabled but enable the version-dependent change |

These fixture APIs are deliberately small JVM public APIs. A/NA are encoded as integer
values in this fixture, not a claim that every production AB SDK uses this shape.
Each negative must produce PASS with no findings or diagnostics, nonzero and fully
proved obligations, and FOLDED_EQUIVALENT reasons. Reanalyzing the identical revisions
with `active.yml` must produce findings without analysis diagnostics. The second check
prevents a silently ignored change from satisfying the negative test.

Results are separate from the shared single-gate corpus metrics. These cases exercise
multiple rules and explicit control scenarios; they do not prove arbitrary heap-based
PR_K propagation, enum-return SDK folding, or all combinations of interacting keys.
