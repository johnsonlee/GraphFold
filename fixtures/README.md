# Detection regression fixtures

The shared fold sets `sample.Ab.enabled("feature")` to false. A positive label means
behavior or an existing API changes outside that assumption. A negative label means
the changed behavior disappears, the code is unreachable under the modeled entry
points, or a supported refactoring preserves behavior. Labels describe expected
semantics and must not be changed merely to accommodate analyzer output.

## Checked-in corpus inventory

| Fixture input | Cases | Positive | Negative | Test code |
| --- | ---: | ---: | ---: | --- |
| `manifest.json` + `cases/` | 130 | 60 | 70 | `DetectionCorpusTest` Java test |
| Same manifest + `kotlin/cases/` | 130 | 60 | 70 | `DetectionCorpusTest` Kotlin test |
| `kotlin/features.json` + `kotlin/cases/` | 56 | 24 | 32 | `DetectionCorpusTest` Kotlin test |

These are 316 language-specific scenarios, including 130 explicitly authored Kotlin
counterparts of Java cases. Counts identify checked-in coverage, not a claim that the
latest test run passed. `DetectionCorpusTest` creates real Git commits, compiles both
worktrees, executes Cypher selection and Graphite folding, and compares verdicts with
labels. Every listed case must match; a blocking diagnostic fails independently and
never counts as a true positive. Exact changed-character coordinates are additionally
asserted for the six uncontrolled Java Stream cases.

Results and confusion-matrix metrics are written under
`engine/build/reports/detection/{java,kotlin}/`. `GRAPHFOLD_CASE_FILTER` runs a selected
subset for debugging; metrics record `filtered`, and a filtered result is not evidence
for the whole corpus.

## Feature coverage

The [Java matrix](java-matrix.md) lists paired cases for control flow and callbacks,
field/method changes, private renames, new-code reachability, class initialization and
inherited overrides. The six Stream pairs cover replacing an intermediate operation,
adding/removing a filter, changing a map lambda, and adding/removing a predicate
conjunct. Each has uncontrolled and explicitly gated versions.

The original 24 Kotlin-specific pairs cover `when`, safe calls/Elvis, smart casts, nullable
comparisons, `let`, `run`, `apply`, `also`, `with`, extension functions/properties,
custom getters, lazy delegation, data-class `copy` and destructuring, default/named
arguments, object expressions, companions, ranges, sequences, inline calls,
nonlocal/labeled returns, and a suspend callback resumed through `Continuation`.
The suspension fixture does not exercise scheduler interleavings or coroutine races.

The [local negative matrix](negative-local.md) adds 12 Java/Kotlin pairs where the
Stream, callback, helper, loop or exception scope survives folding. The
[Kotlin negative matrix](kotlin/negative-features.md) adds eight locally gated
feature cases, including default parameter declarations and custom delegates.
These negatives require nonzero proof obligations and at least one
`FOLDED_EQUIVALENT` reason, so an all-unreachable result cannot satisfy them.

## Additional checked-in integration fixtures

| Input | Cases | Test code | Contract |
| --- | ---: | --- | --- |
| `negative-scenarios/manifest.json` and full trees | 6 | `NegativeScenarioIntegrationTest` | Multi-ID, A/NA and mixed-key negatives; the same changes must be detected under enabled-path rules |
| `moves/manifest.json` and full trees | 6 | `MoveIntegrationTest` | Java/Kotlin directory moves, Kotlin facade rename and stable `JvmName`, Java/Kotlin moves with changed arithmetic |
| `additions/manifest.json` and full trees | 4 | `AdditionIntegrationTest` | New unused and referenced physical source files in Java/Kotlin |
| `initialization/manifest.json` and full trees | 4 | `InitializationIntegrationTest` | Java/Kotlin default-superinterface effects and newly activated initialization from adding an unused default method |
| `reasons/manifest.json` referencing corpus cases | 5 | `ResultReasonIntegrationTest` | Report categories, verdict reasons, and field-change coordinates |
| `analysis-gaps/reflective-dependency/` | 2 | `ReflectiveDependencyIntegrationTest` | Java/Kotlin reflected private field names cannot silently disappear across an external-library boundary |

Pure source moves carry old/new paths and no invented changed-character ranges.
An unchanged-content Kotlin filename rename can change its JVM facade; that case
requires a fail-closed diagnostic and does not contribute a detection true positive.
The reflective dependency test compiles a local library and verifies different runtime
outputs, then requires either a retained difference or an analysis diagnostic; it is
also excluded from corpus accuracy. Reason fixtures reuse existing cases and do not
increase the number of independent detection scenarios.

## Generated-code location regressions

The separate source-location matrix contains **22 base/head pairs: Java 8 and
Kotlin 14**. These do not increase the **316-case detection corpus** or its accuracy
sample size. They are checked-in regression inputs; their presence alone is not a
claim that validation has passed.

| Language | Fixtures and case coordinates | Test entry point | Categories |
| --- | --- | --- | --- |
| Java (8) | [Java location matrix](source-locations/java/README.md) | [JavaGeneratedSourceLocationTest](../engine/src/test/kotlin/io/johnsonlee/graphfold/engine/JavaGeneratedSourceLocationTest.kt) | Uncaptured/captured lambda, anonymous callback, generic bridge; each has an uncontrolled and controlled counterpart |
| Kotlin (14) | [Kotlin location matrix](source-locations/kotlin/README.md) | [KotlinGeneratedSourceLocationTest](../engine/src/test/kotlin/io/johnsonlee/graphfold/engine/KotlinGeneratedSourceLocationTest.kt) | Uncaptured/captured lambda, default parameter, property accessor, generic bridge, suspend state machine, inline body; each has both counterparts |

Both suites create repositories through JGit and run the real engine. Uncontrolled
cases assert the complete set of BASE/HEAD file, line and column locations against
hard-coded one-character diff spans. Controlled cases require no findings; Kotlin
also asserts the precise verdict-reason spans. Blocking diagnostics cannot count as
a successful detection. ASM assertions independently check that the intended
compiler-generated structures exist, rather than inferring them from source syntax.
The Kotlin suspend pairs additionally execute a real suspension and explicit resume.

Generated code does not necessarily carry `ACC_SYNTHETIC`: the tests distinguish
lambda implementations, default stubs and bridges from accessors, anonymous classes
and coroutine structures. These checks establish exact diff anchors even with a
conservative CLASS proof obligation. They do **not** establish a unique reverse
mapping from every synthetic JVM member to a source declaration, nor cover every
compiler version, transformation or asynchronous execution schedule.

## Anonymous compiler-origin regressions

The [anonymous-origin manifest](anonymous-origins/manifest.json) contains 32
base/head pairs: Java 16 and Kotlin 16, with 14 uncontrolled and 18 controlled or
unreachable cases. `AnonymousOriginIntegrationTest` checks real compiler output,
verdicts without blocking diagnostics, and literal diff coordinates. Cases cover
same-line objects, nested objects, field/instance/static initialization, numbering
drift after insertion/reordering, duplicate-looking expressions, and superconstructor
argument changes. The Java argument tests independently require unchanged anonymous class bytecode,
ensuring the caller-side change cannot be lost by class-only binding. Kotlin checks
its actual constructor lowering separately, without assuming Java-equivalent output.

`OriginCatalogTest` separately compiles Java/Kotlin mapping inputs and removes debug
attributes to verify sidecar-based binding; corrupt hashes, ranges, markers and run IDs
must block. `InlineOriginAlignmentIntegrationTest` distinguishes copies emitted in
separate named callers and requires diagnostics for indistinguishable copies inside
one caller. These tests are not additional detection-corpus accuracy samples.

The compiler-version source-mapping matrix lives in
[the reproducible Kotlin probe](../docs/experiments/kotlin-origin/README.md).
It does not claim cross-compiler behavioral equivalence. Final execution and coverage
evidence belongs in [the validation record](../docs/validation.md).

## Limits of this inventory

The new-unused-class corpus case places the new class in an existing source file;
`AdditionIntegrationTest` separately exercises actual new physical files in both
languages. The reflection boundary fixtures use Java and Kotlin applications with
the same Java dependency. The default-interface Kotlin case intentionally uses a
Java interface to ensure actual JVM default-method initialization semantics. Source
move tests include both Java and Kotlin behavior changes and Kotlin facade ABI changes.

This is a development regression set, not an independent production benchmark.
Production acceptance needs a frozen, independently labelled sample of real PRs,
including unsupported inputs; report the diagnostic rate separately from precision
and recall. Newer Java pattern matching, assertion enablement, resource suppression
edge cases, more callback frameworks, and general asynchronous execution remain
outside these fixture claims. Kotlin-specific coverage is the enumerated set above,
not every language feature or compiler mode.

Opaque third-party object-graph escape is a separate analysis boundary: arrays, collections,
`Optional`, application objects, and unmodeled library receivers fail closed unless a model proves
them safe. Even `List<String>` is currently conservative. Scalar-only external calls and supported
JDK/Kotlin callback paths are handled separately. These diagnostics do not contribute true positives
to the regression corpus.

Four additional full-engine runs reuse the controlled/uncontrolled Kotlin same-line fixtures with CRLF and BOM+CRLF. They assert the same verdicts and exact diff line/column ranges. Compiler-provider tests additionally cover physical UTF-16 offsets for astral Unicode, tabs, mixed line endings and inline copies. The persistent compiler matrix validates 20 compiler/frontend/encoding combinations with one unchanged provider binary.
