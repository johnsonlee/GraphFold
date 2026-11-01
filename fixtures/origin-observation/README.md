# Runtime name observation after folding

Every head revision adds an anonymous allocation under the folded-off AB gate before
an unchanged live callback. Both javac and Kotlin therefore change the emitted live
callback name. `OriginObservationReachabilityIntegrationTest` verifies this compiler
fact independently, then runs each base/head pair through the full engine.

| Case | Expected | Observation boundary |
| --- | --- | --- |
| `dead_stack_helper` | PASS | An uncalled private exception factory captures no runtime stack |
| `dead_render_helper` | PASS | An uncalled private renderer observes no object names |
| `folded_observer` | PASS | Folding removes the rendering call even though its containing method remains reachable |
| `unrelated_capture` | PASS | A reachable exception factory has no renamed callback on its call path |
| `reachable_render` | FAIL | Rendering the live callback can expose its changed binary name |
| `transitive_capture` | FAIL | A helper captures a stack containing its renamed anonymous caller |

The original six cases are provided in Java and Kotlin. Additional pairs cover:

| Case | Languages | Expected | Observation boundary |
| --- | --- | --- | --- |
| `folded_concat` | Java, Kotlin | PASS | Folding removes a concatenation or string template rendering the callback |
| `reachable_concat` | Java, Kotlin | FAIL | Surviving concatenation exposes its changed binary name |
| `folded_reference` | Java | PASS | Folding removes the method-reference factory and invocation |
| `reachable_reference` | Java | FAIL | A surviving `this::toString` factory can render the renamed receiver |
| `cross_thread_capture` | Java, Kotlin | FAIL | Reading other threads' stacks cannot be limited to the observer's own callers |

Java compiler checks inspect actual `StringConcatFactory` and `LambdaMetafactory`
bootstrap sites. The complete matrix contains 20 base/head pairs. PASS requires no blocking
diagnostics and no findings. FAIL must report a runtime-name proof gap, rather than
passing solely because anonymous owners were normalized. Every reported finding
must remain anchored to the actual changed source line.
