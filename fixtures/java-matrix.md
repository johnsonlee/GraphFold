# Java control-flow and callback regression matrix

Each row has two independently specified labels. The unprefixed fixture is a positive
(uncontrolled change): at least one input changes its return value, thrown exception,
or externally visible effect when `sample.Ab.enabled("feature") == false`.
Its `gated_` counterpart is a negative (controlled change): both revisions execute the
changed construct only when that same gate is true and return zero otherwise.
The labels describe semantics; they must not be changed to accommodate analyzer output.

The shared manifest contains 130 Java scenarios (60 positive, 70 negative), each with
a Kotlin counterpart. Kotlin adds 56 language-specific scenarios. These fixtures
supplement the existing Stream positive/negative pairs. They run through
the full Git diff, build, source location, Cypher fold, comparison, and verdict pipeline.
A diagnostic is an analysis failure, not a successfully detected positive.

| Positive fixture | Negative fixture | Behavior covered |
| --- | --- | --- |
| `condition_if_else` | `gated_condition_if_else` | if/else boundary at zero |
| `condition_else_if` | `gated_condition_else_if` | else-if boundary at ten |
| `condition_ternary` | `gated_condition_ternary` | conditional expression boundary |
| `condition_switch` | `gated_condition_switch` | switch statement case behavior |
| `condition_switch_expression` | `gated_condition_switch_expression` | Java 17 switch expression |
| `condition_and_add` | `gated_condition_and_add` | add short-circuit conjunction |
| `condition_and_remove` | `gated_condition_and_remove` | remove short-circuit conjunction |
| `condition_or_add` | `gated_condition_or_add` | add short-circuit disjunction |
| `condition_or_remove` | `gated_condition_or_remove` | remove short-circuit disjunction |
| `condition_not` | `gated_condition_not` | negated predicate |
| `condition_for` | `gated_condition_for` | counted for loop bound |
| `condition_enhanced_for` | `gated_condition_enhanced_for` | enhanced for filter |
| `condition_while` | `gated_condition_while` | while termination condition |
| `condition_do_while` | `gated_condition_do_while` | do-while termination condition |
| `condition_break` | `gated_condition_break` | conditional break |
| `condition_continue` | `gated_condition_continue` | conditional continue |
| `condition_labeled_break` | `gated_condition_labeled_break` | labeled transfer out of nested loops |
| `condition_early_return` | `gated_condition_early_return` | early-return guard |
| `condition_throw` | `gated_condition_throw` | throw guard |
| `condition_try_catch` | `gated_condition_try_catch` | exceptional return from catch |
| `condition_finally` | `gated_condition_finally` | finally side effect |
| `callback_lambda` | `gated_callback_lambda` | direct functional interface invocation |
| `callback_captured_lambda` | `gated_callback_captured_lambda` | lambda capturing local state |
| `callback_method_reference` | `gated_callback_method_reference` | replace static method reference |
| `callback_anonymous` | `gated_callback_anonymous` | anonymous class interface callback |
| `callback_foreach` | `gated_callback_foreach` | library invokes Consumer with captured mutable state |
| `callback_future` | `gated_callback_future` | CompletableFuture continuation |
| `callback_optional` | `gated_callback_optional` | Optional mapping callback |

## Scope and outstanding coverage

This is Java 17 source coverage, not a claim to enumerate every Java language construct
or callback framework. Pattern switch from newer Java releases, assertion enablement,
resource suppression edge cases, bound instance references,
constructor references, framework-discovered callbacks, and asynchronous scheduling/race
semantics need dedicated cases and capability decisions. Kotlin counterparts and
Kotlin-only constructs belong in the Kotlin matrix; they must be compiled and analyzed
as Kotlin instead of treating Java source as evidence of Kotlin support.

Positive/negative pairs use explicit outer gating to isolate whether each construct's
changed behavior survives folding. They do not establish arbitrary interprocedural gate
propagation, predicate equivalence, or production precision/recall.

## Newly introduced code reachability

New public declarations do not become entry points merely by being public.
`new_public_method` and `new_unused_class` are negative: no existing code references
the added declarations. `new_called_public_method` and `new_referenced_class` are
positive: an existing entry point invokes the newly introduced behavior. These
labels implement the user-specified scope of newly introduced, unreferenced code.
The single-file corpus variant shares a source file with the existing entry point.
`AdditionIntegrationTest` separately loads four full-tree fixtures covering actual new
unused/referenced Java and Kotlin files.

## Additional control scopes

| Positive fixture | Negative fixture | Behavior covered |
| --- | --- | --- |
| `condition_labeled_continue` | `gated_condition_labeled_continue` | Labeled transfer to next outer iteration |
| `condition_synchronized` | `gated_condition_synchronized` | Changed behavior within monitor scope |
| `condition_resource_scope` | `gated_condition_resource_scope` | Try-with-resources / Kotlin use with read sequence change |

## Field relevance and initialization

| Fixture | Expected | Reason |
| --- | --- | --- |
| `unused_existing_field` | PASS | Unread private pure constant changes; storage alone is outside observable behavior |
| `referenced_existing_field` | FAIL | Existing method return depends on changed field value |
| `unused_new_field` | PASS | New unread private field has pure constant initializer |
| `effectful_new_field` | FAIL | New unread field initializer prints when existing class is constructed |

These cases assume existing public classes can be instantiated. A field's unread value
does not make its initializer side effects irrelevant. Resource-scope fixtures exercise
ordinary close cleanup; they do not prove suppressed-exception equivalence.

## Symbol rename refactoring

| Fixture | Expected | Reason |
| --- | --- | --- |
| `private_field_rename` | PASS | Private field and every direct reference renamed, value preserved |
| `private_field_rename_value_change` | FAIL | Rename also changes the value returned by an existing entry point |
| `private_method_rename` | PASS | Private method and every direct call renamed, body preserved |
| `private_method_rename_body_change` | FAIL | Rename also changes the method result |
| `public_method_rename` | FAIL | An existing public API name is removed despite updated internal callers |

These examples contain no reflection, serialization, annotations, JNI, or framework
name binding. If external name binding cannot be excluded for a proposed rename, the
analysis must report that gap rather than classify its fail-closed verdict as a correctly
detected positive. Renaming compiler-private implementation symbols is different from
renaming an existing public API.

## JVM class initialization

All cases retain an unchanged gate anchor. Existing `Example` is a production entry
class and may be instantiated; its initializer can therefore affect observable behavior.
Additional internal types are relevant only when existing code triggers initialization.

| Fixture | Expected | Reason |
| --- | --- | --- |
| `static_block_effect` | FAIL | Existing class initializer changes emitted output |
| `gated_static_block_effect` | PASS | Changed static-block effects are eliminated by the fold |
| `new_unreferenced_static_block` | PASS | New type is never initialized by existing code |
| `new_referenced_static_block` | FAIL | Existing method initializes new type with an output effect |
| `static_field_initializer_effect` | FAIL | Unread static field initializer changes output |
| `gated_static_field_initializer_effect` | PASS | Static field helper changes only inside disabled gate |
| `static_initializer_throws` | FAIL | Existing type starts failing initialization |
| `gated_static_initializer_throws` | PASS | Changed exception detail exists only inside disabled gate |
| `constant_reference_static_block` | PASS | Inlined compile-time constant read does not initialize its declaring type |

Kotlin equivalents use companion initialization for existing class initialization and
object initialization for additional types. `const val` models a JVM compile-time
constant. These examples do not cover reflective initialization, concurrent initialization
ordering, custom class loaders, or failure caching across distinct class loaders.

## New inherited overrides

`new_inherited_override` is positive in Java and Kotlin. Existing Example extends
ArrayList; newly overriding isEmpty() to always return true changes an inherited API
for nonempty instances. Absence of direct application callers does not make an override
unreferenced: external callers can already invoke the inherited virtual method.

## Integration tests beyond the single-file corpus

`MoveIntegrationTest` loads six full-tree cases from `moves/manifest.json` and
verifies actual Git moves, facade identity, reasons, paths, and real changed-character
locations. `InitializationIntegrationTest` loads four full-tree cases to cover default
superinterface initialization and added-default-method initialization prerequisites from Java and Kotlin production classes.
`ReflectiveDependencyIntegrationTest` uses checked-in Java/Kotlin application and Java dependency
sources to prove an externally observed private-field rename cannot be normalized
away. `ResultReasonIntegrationTest` loads five report expectations that reuse corpus
cases. These tests are executable counterparts of the documented cases; their
fail-closed diagnostics do not inflate corpus accuracy.

## Retained execution negative cases

See [negative-local.md](negative-local.md) for 12 additional Java/Kotlin pairs whose
outer execution remains after folding. Unlike whole-block outer-gate cases, these
exercise gates inside stream operations, callbacks, shared helpers and loops.
See [negative-scenarios](negative-scenarios/README.md) for per-fixture multi-ID, A/NA
and mixed-key assumptions with enabled-path detection checks.
