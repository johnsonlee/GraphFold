# Kotlin callable references

`CallableReferenceIntegrationTest` runs these real base/head Kotlin revisions through the engine.
`equivalent_expression` adds parentheses around a callable-reference invocation and must pass;
`changed_target` changes the referenced method and `changed_body` changes its implementation, and both must fail.
Every case retains an SDK call so graph selection and folding execute.

The shared provider fixture in `gfc/src/test/resources/kotlin/callable-references/References.kt` covers top-level,
bound, unbound and Java method references, plus bound/unbound read-only and mutable property references.
Provider tests compile it through K1 and K2; the engine test also validates its emitted origin catalog.
All eight callable-reference implementations must have declaration provenance, while its actual object literal
still requires anonymous-object provenance and source pairing.
