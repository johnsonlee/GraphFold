# Frontend diagnostics

Java and Kotlin enum constants contain Graphite diagnostic phrases and embedded
newlines. These are analyzed program data, not frontend failures. Each language
has a controlled change that must PASS and a surviving change that must FAIL with
a diff-owned finding. `FrontendDiagnosticsIntegrationTest` executes all four pairs;
`GraphiteDiagnosticsTest` separately verifies genuine diagnostic prefixes and the
actual verbose enum-extraction callback.
