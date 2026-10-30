# Anonymous class source origins

These fixtures exercise actual Java anonymous classes and Kotlin object expressions,
not lambda syntax that a compiler may lower to `invokedynamic`. Each fixture compiles
both revisions with the production compiler. `AnonymousOriginIntegrationTest` checks
that callback class files and method line tables exist before checking the verdict.
The manifest contains exact one-based, end-exclusive changed-token coordinates.

| Case (both languages) | Expected | What it establishes |
| --- | --- | --- |
| `earlier_folded_allocation` | PASS | Adding an earlier callback allocation under the folded-off gate renumbers the live callback in the same method |
| `earlier_unused_declaration` | PASS | An added private unused method must not change the live callback's behavior; javac ordinals drift, Kotlin method-scoped names remain stable |
| `reorder_unused_declaration` | PASS | Moving an existing unused private factory before the live method only changes source order and javac ordinals |
| `same_line` | FAIL | A changed second callback on the same source line must point to its own changed token |
| `same_line_controlled` | PASS | The same-line body change disappears under the control assumption |
| `nested` / `nested_controlled` | FAIL / PASS | Nested anonymous callback owners retain the innermost changed source anchor |
| `field_initializer` / `field_initializer_controlled` | FAIL / PASS | A callback allocated as a field initializer remains attributable |
| `instance_initializer` / `instance_initializer_controlled` | FAIL / PASS | Instance initialization allocation is associated with its source construct |
| `static_initializer` / `static_initializer_controlled` | FAIL / PASS | Java static-block / Kotlin companion-init allocation remains attributable |
| `identical_same_line` | FAIL | Identical callback bodies in base do not permit matching solely by body fingerprint; only the first expression changes |

The matrix contains **28 base/head pairs: 14 Java and 14 Kotlin**. The compiler fact
assertion identifies the live callback by its unchanged `value + 10` implementation
and compares the actual emitted owner names. It requires drift where expected rather
than assuming `$1`, `$2`, or a source line is a stable identity.

`mapping/{java,kotlin}/identical_expressions` and `missing_debug` are mapper-focused
inputs. The latter is compiled normally then has `SourceFile`, line numbers, and local
variable tables removed in the mapper test. A mapper must either establish a unique
creation-site correspondence from remaining evidence or explicitly report unavailable
or ambiguous provenance. Equal body hashes, same-line locations, or numeric suffixes
alone must never select a guessed origin. These inputs do not grant permission to
ignore an unanalyzed changed callback.

The fixture verdicts are contracts, not a claim of passing verification. Keep the
full verdict assertion, zero-blocking-diagnostic requirement, and exact location
assertions when implementing the mapper; do not weaken them to accommodate a mismatch.

Constructor-argument regressions: `super_argument` changes the value passed to the anonymous superclass. javac leaves anonymous class bytecode unchanged and places the argument in the caller; Kotlin 2.1.20 lowers the constant into the generated constructor. The allocation/header obligation must FAIL in both cases. `super_argument_controlled` gates that allocation with the selected experiment and must PASS. Both Java and Kotlin assert exact changed literal locations.
