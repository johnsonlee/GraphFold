# Detection regression fixtures

The shared fold sets `sample.Ab.enabled("feature")` to false. A positive label
means a production change remains outside that selected control. A negative label
requires static evidence that folding the selected control removes the change or
that the changed structure is equivalent. An uncalled method, uninvoked lambda,
unconsumed Stream, or equal observed output cannot excuse an uncontrolled edit.
Labels must not be changed merely to accommodate analyzer output.

## Checked-in corpus inventory

| Fixture input | Cases | Positive | Negative | Test code |
| --- | ---: | ---: | ---: | --- |
| `manifest.json` + `cases/` | 134 | 67 | 67 | `DetectionCorpusTest` Java test |
| Same manifest + `kotlin/cases/` | 134 | 67 | 67 | `DetectionCorpusTest` Kotlin test |
| `kotlin/features.json` + `kotlin/cases/` | 77 | 38 | 39 | `DetectionCorpusTest` Kotlin test |

These are 345 language-specific scenarios, including 134 explicitly authored Kotlin
counterparts of Java cases. Counts identify checked-in coverage, not a claim that the
latest test run passed. `prepareDetectionCorpus` creates real Git revisions and
compiles each case once. `DetectionCorpusTest` consumes those prepared inputs, runs
the static engine against the Git revisions with Cypher selection and Graphite
folding, and compares verdicts with labels. Every listed case must match; a blocking diagnostic fails independently and
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

### Primary packaged Web applications

`SpringBootArchiveGoldenTest` builds real Spring Boot JARs with the official
packaging plugin. `ServletWarGoldenTest` builds real WARs with business code in
`WEB-INF/lib` and build-declared servlet API dependencies. Both assert static
control-coverage verdicts and source locations against the packaged bytes. Neither
starts the application or a container. These main paired cases require two complete
PASS results and two located FAIL results; diagnostic-only failures are not detections.

### Flagr business inputs

`FlagrControlGoldenTest` packages a price service in Boot and WAR layouts. Its
business code contains the real HTTP/JSON client calls, but the test does not run
that client or start Flagr. The selected decision is folded to both boolean values.
Changes to the enabled and fallback prices give eight static semantic cases across
the two layouts. Reports under `adapters/build/reports/flagr` retain analysis
results without runtime observations.

The application context dimensions are:

| Dimension | All | Single | Multiple | Domain |
| --- | --- | --- | --- | --- |
| Platform | No constraint | Android | Android, iOS | Android, iOS, Web |
| Region | No constraint | KR | KR, US | KR, US, JP |

Historical tests exercised 81 request contexts against a running Flagr evaluator.
Those observations were never 81 independent static proofs and are no longer part
of the test suite. The static verdict labels depend on the edited branch and the
selected control, not on any request assignment or observed price.

`AdvancedFlagrControlGoldenTest` retains six packaged implementations: captured
lambdas/custom functional interfaces, bound and unbound method references, static
MethodHandle lookup with `invokeExact`, final-receiver `bindTo` with `invoke`, and
a serial `filter/map/collect/reduce` pipeline. It inspects actual nested business
bytecode before checking the same positive/negative static labels and source paths.

`LazyFlagrProductionChangeGoldenTest` checks an uncontrolled lambda-body edit in an
unconsumed Stream. Both layouts must report the production change; no invocation or
observed output is needed to establish the expected FAIL.

`ExposureFlagrControlGoldenTest` checks new and existing exposure predicates before
the selected decision for all nine combinations of all/single/multiple platform and
region selection. Each archive contains 18 control calls: nine existing-exposure
price edits and eight new predicates; the all/all new-exposure case has no predicate
to add and remains unchanged. AB=false must prove coverage; AB=true must locate all
17 edited sources. Separate compiler
goldens cover primitive/private-helper and AND/OR conditions, nullable inputs,
exceptions, side effects, altered fallbacks and unrelated conditions.

| Input | Cases | Test code | Contract |
| --- | ---: | --- | --- |
| `negative-scenarios/manifest.json` and full trees | 6 | `NegativeScenarioIntegrationTest` | Multi-ID, A/NA and mixed-key negatives; the same changes must be detected under enabled-path rules |
| `moves/manifest.json` and full trees | 6 | `MoveIntegrationTest` | Java/Kotlin directory moves, Kotlin facade rename and stable `JvmName`, Java/Kotlin moves with changed arithmetic |
| `additions/manifest.json` and full trees | 6 | `AdditionIntegrationTest` | New unused, uncontrolled referenced and selected-control-covered physical source files in Java/Kotlin |
| `initialization/manifest.json` and full trees | 4 | `InitializationIntegrationTest` | Java/Kotlin default-superinterface effects and newly activated initialization from adding an unused default method |
| `reasons/manifest.json` referencing corpus cases | 6 | `ResultReasonIntegrationTest` | Report categories, verdict reasons, and field-change coordinates |
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
Kotlin 14**. These do not increase the **345-case detection corpus** or its accuracy
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
[the reproducible Kotlin probe](../docs/research/compiler/README.md).
It does not claim cross-compiler behavioral equivalence. Final execution and coverage
evidence belongs in [the validation record](../docs/validation.md).

Packaged golden tests build and inspect artifacts, then assert static verdicts.
They do not start applications, containers or HTTP evaluators.

## Limits of this inventory

### Verdict acceptance and independent evidence

The 345 source-corpus scenarios contain 172 expected differences and 173 expected
equivalences. They include 134 Java/Kotlin counterpart pairs, not 345 independent
samples. The corpus compiles source-only fixtures; its accuracy does not validate
build-declared artifact selection, archive normalization, or source-provenance export.
Line/branch coverage and matching these development labels do not establish that
verdicts have converged on real applications.

Deployment acceptance must distinguish proved equivalence, detected difference,
incomplete/unsupported analysis, and operational failure. The public API uses FAIL
for the latter three, so a FAIL assertion alone is insufficient. An unsupported or
incomplete result is not a detection true positive. Report these outcomes separately,
including incomplete cases by their semantic label, instead of silently excluding
them from an accuracy claim. An unsafe PASS remains a false negative.

For a supported feature, use paired full-engine fixtures with actual packaged
BASE/HEAD artifacts: a behavior-changing case must produce a finding without a
blocking diagnostic, and a behavior-preserving case must PASS with completed proof.
Fix expected outcomes before inspecting analyzer results. Executed witnesses provide
independent evidence for the selected inputs; they are not a proof of equivalence for
all inputs. Unsupported-loader fixtures separately assert the exact blocking reason
and must include ordinary-resource controls so that broad rejection cannot masquerade
as improved detection. Development regressions still need a frozen, independently
labelled real-project holdout before supporting a general accuracy claim.

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

Opaque third-party object-graph escape is a separate analysis boundary. Scalar-typed arrays
(including nested arrays, field reads and element loads) cannot carry application-object payloads;
unknown `Object[]` and application-object arrays retain their observation guards. Exact immutable
factories and static/private/final application helper calls preserve proven scalar-only payloads,
including local aliases and distinct safe/unsafe argument contexts. A declared `List<String>` alone
still does not establish its contents. Mutable object graphs, unknown field contents, overridable
helpers and unmodeled library receivers remain guarded. These payload proofs do not erase array
mutation, resource effects or diagnostics inside helpers. Recursion and the bounded helper-depth
budget cannot produce a proof. Provided dependencies additionally need a closed bytecode observation
proof for the exact callee and its initialization closure. The reflective dependency regression
executes a zero-argument library call whose hidden reflection observes a changed private application
method; it must retain a finding or gap despite receiving no application-object argument.
These diagnostics do not contribute true positives to the corpus.

Four additional full-engine runs reuse the controlled/uncontrolled Kotlin same-line fixtures with CRLF and BOM+CRLF. They assert the same verdicts and exact diff line/column ranges. Compiler-provider tests additionally cover physical UTF-16 offsets for astral Unicode, tabs, mixed line endings and inline copies. The persistent compiler matrix validates 20 compiler/frontend/encoding combinations with one unchanged provider binary.
