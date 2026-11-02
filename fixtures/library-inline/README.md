# Library inline generated declarations

Real Kotlin `sortedBy` emits an inlined comparator from the standard library. These fixtures require compiler provenance to accept its synthetic declaration category without inventing a checkout anonymous-object origin or aligning on generated names/body hashes.

- `equivalent_expression`: unchanged selector and redundant parentheses pass.
- `folded_change`: only the PR-key-controlled return changes; the same comparator survives and the change passes.
- `changed_selector`: the comparator selector changes from ascending to descending length and must produce a diff-located violation.

`LibraryInlineIntegrationTest` runs every pair through the engine and verifies catalog acceptance separately. `KotlinOriginPluginIntegrationTest` verifies emitted metadata/enclosing-method context and executable ordering with both K1 and K2. The compiler collector still rejects missing/ambiguous enclosing provenance and stale/corrupt copied markers. Generated implementation classes retain their actual JVM identities: renumbering is not normalized by this classification and may conservatively fail equivalence.
