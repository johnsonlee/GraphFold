# Verdict reason fixtures

`manifest.json` references the existing `fixtures/cases/<caseId>/{base,head}` sources and common fold rule. These are full Git/build/Graphite/source-location integration cases, not fabricated result objects.

Each row asserts both the verdict and a reason combining an AST change category with a proof outcome. The field-initializer row additionally pins exact diff coordinates. Uncontrolled additions require FAIL even when uninvoked; PASS requires a completed static control proof. Rename labels describe structural rename candidates; the proof engine must independently establish equivalence.
