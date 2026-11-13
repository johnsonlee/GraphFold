# Static control coverage label corrections

GraphFold checks whether production changes are covered by selected controls. A lack
of calls or equal observed output is insufficient evidence of control coverage.
These labels follow the source changes below, independently of analyzer results.

| Corpus case | Source change outside selected controls | Correct label |
| --- | --- | --- |
| [new_public_method](../../../fixtures/cases/new_public_method/head/Example.java) | Adds `added()` containing a print; the existing AB call is in `run()` | FAIL |
| [new_unused_class](../../../fixtures/cases/new_unused_class/head/Example.java) | Adds `Additional.apply()` outside the existing class's AB method | FAIL |
| [unused_existing_field](../../../fixtures/cases/unused_existing_field/head/Example.java) | Changes the private field initializer from 1 to 2 | FAIL |
| [unused_new_field](../../../fixtures/cases/unused_new_field/head/Example.java) | Adds a private field initializer outside any selected control | FAIL |
| `additions/java_unused_file`, `additions/kotlin_unused_file` | Adds a separate production source file with no controlling call | FAIL |
| [earlier_unused_declaration, Java](../../../fixtures/anonymous-origins/java/earlier_unused_declaration/head/Example.java), corresponding Kotlin case | Adds a private method and its anonymous implementation outside AB control | FAIL |

The shared four-case labels also apply to their Kotlin counterparts. Reordering an
unchanged declaration is not relabeled merely because its name includes “unused”.
Private rename alignment and compiler-origin identity remain separate structural
proofs; these changes do not treat compiler-generated names as source modifications.

The direct `PreparedInputGoldenTest` supplies normalized classes, source ranges,
content identities and explicit provenance without repository/build/archive adapters.
It distinguishes never-called private edits from helper code whose same-revision
pre-fold path is removed by a positively applied selected control. Added helper
code must supply that proof in every revision where it exists. Removing an unrelated
caller in source does not supply a folding receipt. Surviving lambda factories retain
callback bodies as static production references, without a runtime-consumption claim.

These are corrected expectations and focused regression tests, not a new precision,
recall, coverage or performance measurement. Full corpus results must be regenerated.
