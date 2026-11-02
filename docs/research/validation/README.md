# Cross-module validation

The [latest review validation](provenance-validation-2026-11-02.json) accompanies the [validation report](../../validation.md). The [gfc extraction baseline](main-validation.json) preserves the previous full check. The other JSON files preserve earlier quality-gate runs, including anonymous origins, object flow, incremental builds, workspace capacity, and fold auditing. Their timestamps and counters describe those historical implementations, not the current checkout.

- [anonymous-origin-validation-2026-10-30.json](anonymous-origin-validation-2026-10-30.json)
- [fold-audit-validation-before-enums-2026-10-31.json](fold-audit-validation-before-enums-2026-10-31.json)
- [incremental-object-flow-validation-2026-10-31.json](incremental-object-flow-validation-2026-10-31.json)
- [main-validation-before-anonymous-origins.json](main-validation-before-anonymous-origins.json)
- [review-validation-before-capacity-2026-10-31.json](review-validation-before-capacity-2026-10-31.json)

The [real multi-module provenance probe](provenance-self-analysis-2026-11-02.json) separately records successful origin validation and a remaining duplicate project-class/JAR graph limitation. It is not an end-to-end success or performance result.
