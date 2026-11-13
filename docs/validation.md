# Validation record

## Native integration verification

Focused working-tree verification now includes actual Maven 3.9.9 packaging and
published-plugin invocation for library JARs, Spring Boot 3.5.7 JARs and WARs on
JDK 17. All three controlled cases passed and all three uncontrolled cases failed
with exact source findings, matched controls and no analysis diagnostics. No
application or container was started. The reproducible harness is documented in
[verification tooling](../tools/verification/README.md).

This run reused six unchanged Graphite 2.15.0 JAR/POM files from the existing Gradle
cache after fresh GitHub Packages access returned 401. Their hashes and raw Maven
logs remain outside the repository. It exposed and verified the fix for an ASM
publication mismatch: declared dependencies now match ASM 9.10.1 already selected
by the Gradle build.

The Git-owned diff API, WAR provided API export, safe and unsafe Boot/WAR exposure
predicates, and current-build-JDK MR selection have focused regression coverage.
These results do not replace final-head full tests, exact coverage, publication
or hosted PR checks. Performance acceptance is deferred while correctness and
paired positive/negative coverage take priority.

## Requested golden contracts

This matrix inventories assertions in the tests; it is not an execution report.
Current-head full test and coverage reports establish execution evidence. Paired
cases provide bounded evidence for these mechanisms, not a universal precision/recall guarantee.
Expected verdicts follow static control coverage, independently of observed output.

| Requested scenario | Expected contract | Tests and fixtures |
| --- | --- | --- |
| Spring Boot 3.5.7 JAR and nested business library | Treatment change with the selected control folded off: PASS; folded on: FAIL with the nested-library source finding. Both require no diagnostics and actual archive checks. | [SpringBootArchiveGoldenTest](../plugin/gradle/src/test/kotlin/io/johnsonlee/graphfold/gradle/SpringBootArchiveGoldenTest.kt) |
| WAR and nested business library | Treatment-only change: PASS; added fallback change: FAIL with a precise finding and `SURVIVING_DIFFERENCE`. Both require a matched control, no diagnostics and actual WAR checks. Native Maven Boot/WAR cases also assert both fold states. | [ServletWarGoldenTest](../plugin/gradle/src/test/kotlin/io/johnsonlee/graphfold/gradle/ServletWarGoldenTest.kt), [PackagedMavenGoldenTest](../plugin/maven/src/test/kotlin/io/johnsonlee/graphfold/maven/PackagedMavenGoldenTest.kt) |
| Platform and region exposure | Each of the nine combinations below covers a newly added safe predicate and a treatment edit behind an existing predicate. Fold off: PASS with all obligations proved; fold on: FAIL with exactly the changed source set. Both Boot and WAR require no diagnostics and 18 matched calls. | [ExposureFlagrControlGoldenTest](../plugin/gradle/src/test/kotlin/io/johnsonlee/graphfold/gradle/ExposureFlagrControlGoldenTest.kt), [ExposureFlagrBusinessProject](../plugin/gradle/src/test/kotlin/io/johnsonlee/graphfold/gradle/ExposureFlagrBusinessProject.kt) |
| Treatment versus fallback edits | A change in the branch removed by the selected control: PASS; a change in the retained branch: FAIL. Tests cross both branch mutations, both fold values and both archive formats, with source findings and no diagnostics. | [FlagrControlGoldenTest](../plugin/gradle/src/test/kotlin/io/johnsonlee/graphfold/gradle/FlagrControlGoldenTest.kt) |
| Six advanced Java forms | Captured lambda through a functional interface; bound method reference; unbound method reference; static method handle with `invokeExact`; bound virtual handle with `invoke`; Stream `filter`/`map`/`collect`/`reduce`. Every form participates in the same treatment/fallback PASS/FAIL pairs in Boot and WAR. FAIL must identify all six changed sources, with no diagnostics. Packaged bytecode checks confirm the intended instructions exist. | [AdvancedFlagrControlGoldenTest](../plugin/gradle/src/test/kotlin/io/johnsonlee/graphfold/gradle/AdvancedFlagrControlGoldenTest.kt), [AdvancedFlagrBusinessProject](../plugin/gradle/src/test/kotlin/io/johnsonlee/graphfold/gradle/AdvancedFlagrBusinessProject.kt), [AdvancedBusinessBytecode](../plugin/gradle/src/test/kotlin/io/johnsonlee/graphfold/gradle/AdvancedBusinessBytecode.kt) |
| Unsafe exposure predicates | Effectful counter increment, potentially throwing division, and a predicate result reused outside the control all remain FAIL with precise findings after folding off. The safe predicate in the same fixture has no finding when folded off. Folding on finds all four changes. Both archive formats require no diagnostics. | [UnsafeExposureFlagrGoldenTest](../plugin/gradle/src/test/kotlin/io/johnsonlee/graphfold/gradle/UnsafeExposureFlagrGoldenTest.kt) |
| Unconsumed and uninvoked callbacks | Changing an unconsumed Stream callback without control coverage: FAIL with the exact source finding in Boot and WAR. Core cases also require FAIL for an uninvoked `IntSupplier` and an unconsumed Stream; source-corpus gated/ungated callback and Stream pairs supply the corresponding control-coverage checks. | [LazyFlagrProductionChangeGoldenTest](../plugin/gradle/src/test/kotlin/io/johnsonlee/graphfold/gradle/LazyFlagrProductionChangeGoldenTest.kt), [PreparedInputGoldenTest](../engine/src/test/kotlin/io/johnsonlee/graphfold/engine/PreparedInputGoldenTest.kt), [corpus labels](../fixtures/manifest.json), [DetectionCorpusTest](../engine/src/test/kotlin/io/johnsonlee/graphfold/engine/DetectionCorpusTest.kt) |

The exposure fixture uses platform sets **all**, **Android**, **Android + iOS** and
region sets **all**, **KR**, **KR + US**, crossing them as follows:

| Platform \ Region | All | KR | KR + US |
| --- | --- | --- | --- |
| All | AllAll | AllSingle | AllMultiple |
| Android | SingleAll | SingleSingle | SingleMultiple |
| Android + iOS | MultipleAll | MultipleSingle | MultipleMultiple |

There are 17 changed source files: nine existing-exposure treatment edits and
eight newly added predicates. The all/all new-predicate case has no predicate to
add and therefore no source edit; its control call remains in the 18-call audit.

## Static input and control-coverage changes

The prepared-input engine is checked independently from repository/build adapters.
Golden cases assert static verdicts for packaged JAR/WAR inputs, selected controls,
uncalled callbacks, lambdas, Stream pipelines and safe exposure predicates. Missing
source mappings limit verdict precision without preventing graph construction.
Focused results do not replace final-head CI and exact per-module coverage gates.

The last measured implementation (`c73ae68`) compared all twelve application scopes
in each request (24 BASE/HEAD graphs), using a shared 600-class dependency, JDK 17
and `-Xmx2g`. One warm-up per implementation was discarded, followed by three
alternating pairs against `a8b3aa5`. Median elapsed time was 9.128 s versus 9.974 s
(+9.26%); median sampled peak used heap was 626.5 MiB versus 627.3 MiB (+0.13%).
Both met the historical 10% time / 15% heap regression limits with identical proof
results. This synthetic workload is preserved as historical data only; it is not
performance acceptance evidence under the current representative-load requirement.
Heap sampling excludes build subprocesses and is a lower bound, not minimum Xmx.
No current performance acceptance is claimed.

Raw measurements are kept outside the source tree; CI runs should retain them as
artifacts. Historical single-graph results are not performance acceptance evidence.

## Historical Graphite 2.15.0 release integration (2026-11-11)

The dependency catalog and verdict metadata target the official `2.15.0` release,
tag commit `58215b986f8c1be68b0480bc209e00311bc80dc1`. The observer API is unchanged
from alpha1; the release fixes Cypher inline predicate filtering. Existing alpha1
results below are retained as historical evidence, not release acceptance.

The upstream [publication run](https://github.com/johnsonlee/graphite/actions/runs/42929495020)
successfully published Maven artifacts to GitHub Packages. Its Sonatype release
step failed with HTTP 400, even though the enclosing job completed successfully;
Central returned HTTP 404 for the release POM. GraphFold checks Central first and
then the upstream Packages registry, restricted to `io.johnsonlee.graphite`.
Local credentials use user Gradle properties `graphitePackagesUsername` and
`graphitePackagesToken`, or `GITHUB_ACTOR` and `GITHUB_TOKEN`. CI uses its workflow
token with package read permission; upstream package access is also required.
No local source-built replacement or temporary repository is configured.

At that revision, release acceptance used `./gradlew check :cli:installDist
--no-daemon --stacktrace`, followed by the exact-count coverage collector on both
Ubuntu and macOS. The [PR checks](https://github.com/johnsonlee/GraphFold/pull/2/checks)
and their attached coverage/test reports record the outcome for each revision;
only passing checks for the final head establish hosted release acceptance. The
initial local CLI token could not read Packages (HTTP 401), so local alpha1 results
must not be relabeled as release verification. Detection and coverage requirements
are unchanged.

## Historical alpha1 acceptance: complete coverage and independent publication (2026-11-11)

The configured hard gates now require **100% LINE and 100% BRANCH coverage in
each module**: `engine`, `cli`, `gfc`, and `gradle-plugin`. No production classes
are excluded. CI additionally checks the exact integer counters: any missed line
or branch fails, as do missing/malformed reports and zero measured totals. A
rounded percentage cannot produce a false pass. Both Ubuntu and macOS must
succeed through the existing required `run-unit-test` check.

**Local full regression acceptance passed.** The unfiltered `check`, production
and test type-aware detekt, and every 100% coverage gate succeeded. All **1,494
JVM test invocations** completed with zero failures, errors, or skips. No test
filter, diagnostic exclusion, or merged execution-data report was used for this
acceptance run. Its local log is `/tmp/graphfold-full-check-4.log`.

| Module | Tests | Covered lines / total | Covered branches / total |
| --- | ---: | ---: | ---: |
| engine | 1,339 | 5,171 / 5,171 | 4,815 / 4,815 |
| cli | 30 | 82 / 82 | 34 / 34 |
| gfc | 96 | 364 / 364 | 242 / 242 |
| gradle-plugin | 29 | 95 / 95 | 18 / 18 |

All **132 Java and 209 Kotlin corpus cases** passed without operational blockers
or label mismatches. The added interface-initialization cases also verify actual
JVM output: adding a first concrete instance interface method can activate its
initialization even without invoking that method, including private methods.
Missing-body, provenance, resource, ambiguous-owner, and speculative-reachability
counterexamples remain blocking. The original 22 failures and later initialization
regressions are resolved without reducing thresholds or excluding production code.

The strict CI collector accepted these exact counts after previously rejecting a
report with three missed branches despite complete line coverage. SHA-256 checks
confirmed that all 270 recorded production/test files remained unchanged during
the acceptance run. Commands:

```sh
./gradlew check detektMain detektTest --continue \
  -I /tmp/graphfold-alpha1-consume.init.gradle \
  -I /tmp/graphfold-test-progress.init.gradle
python3 .github/scripts/coverage_report.py --platform macos-latest \
  --output /tmp/graphfold-final-local-coverage.json
```

Verification ran locally on macOS/JDK 17 and resolves the three Graphite
`2.15.0-alpha1` coordinates from the isolated exact-tag build described below.
It does not establish hosted Ubuntu/macOS CI success or clean Maven Central
consumption: the alpha1 POM was still unavailable when checked on 2026-11-11.
The temporary repository is not configured in the project. No performance
acceptance is claimed.

The PR reporting implementation maintains one marked `github-actions[bot]`
coverage comment per PR, updating it and deleting duplicate owned comments.
It shows every module's line/branch counts on both platforms, platform job
results, commit, run attempt, and a link to the Build run. A trusted-default-branch
`workflow_run` consumes bounded JSON artifacts; PR code never runs with the
comment-writing token. API-owned PR associations, unique-only fallback matching,
per-PR queued concurrency, and repeated head/run checks prevent cross-PR or stale
updates. Invalid artifacts and partial attempts remain explicitly incomplete.
No live comment has been posted; hosted behavior remains unverified until the
workflow is merged into the default branch.

All **40 Python tests** for release validation and coverage reporting pass.
Build and Publish pass actionlint. The reporting workflow's only outstanding lint
error is `queue: max`: current GitHub supports it, while actionlint 1.7.12 and its
current upstream parser do not. No lint suppression was added. The detailed
reporting evidence and limitation are recorded in [CI coverage reporting](#ci-coverage-reporting-2026-11-11).

Independent publication has passed two local checks: the fixture accepts matching
signed Central/unsigned Packages payloads and rejects changed JAR/POM/module
metadata; the actual four GraphFold modules and plugin marker also passed guarded
Packages publication to a file repository with complete payload equality.
Maven Central failure cannot block the independent Packages or GitHub Release
jobs after shared preparation succeeds. No remote publication has been attempted;
package permissions and hosted preparation remain unverified. See
[independent publication channels](#independent-publication-channels-2026-11-10)
for the rehearsal boundary.

## Graphite 2.15.0-alpha1 selection (2026-11-10)

The dependency catalog, verdict metadata, and current documentation target
`2.15.0-alpha1`, following the decision to use the available upstream tag instead
of rc1. The rc1 attempt failed before compilation because all three Graphite
coordinates were unavailable from Maven Central; no rc1 tests ran.

The alpha1 Central POM still returns HTTP 404, and the offline consumer has no
cached Central artifact. Verification therefore uses an independent shared-object
checkout of tag `v2.15.0-alpha1`, commit
`907bc9adf24dbd238d72c08127827e6481cc6fe7`, built into an isolated temporary Maven
repository. Publishing `core`, `cypher`, and `sootup` to that local repository
succeeded. Only temporary local signing is disabled; the project repository and
release signing settings are unchanged. This does not establish availability or
verification of signed Central binaries.

Focused observer, dependency-resource and completeness regression checks passed
**74 tests**, zero failures/errors/skips, with engine production/test type-aware
detekt tasks successful (up-to-date). The command was:

```sh
./gradlew -I /tmp/graphfold-alpha1-consume.init.gradle :engine:test \
  --tests '*SnapshotCaptureTest' --tests '*ObservedGraphIntegrationTest' \
  --tests '*Graphite*CompletenessTest' --tests '*DependencyResourceIntegrationTest' \
  :engine:detektMain :engine:detektTest --console=plain
```

The temporary consumer init script selects only the three exact alpha1 coordinates
from `/tmp/graphfold-alpha1-maven`. `git diff --check` also passed.

Existing full-suite XML reports inspected before this run contained
1,143 engine test invocations and 22 failures: five resource-read expectations,
two initialization cases, one fold-selection audit case, one origin-diagnostic
identity case, eleven origin-observation reachability cases, and both corpus
suite methods. The affected paths now pass targeted regression runs, including both corpus
methods restricted to their originally failing `new_called_public_method` case.
This is not yet a complete corpus or full-check acceptance result. Additional
failure-path and boundary tests pass (123 tests in the latest targeted batch).

At this stage, the branch coverage hard gate was raised to **100% in every
module**, with the then-existing 90% line gate retained. No production classes
were excluded. CLI, GFC and Gradle plugin branch gates had passed; engine
acceptance still awaited the unfiltered full suite and remaining coverage fixes.
This historical stage did not establish performance acceptance.

## Graphite observed-load integration (2026-11-07)

The integration targets `2.15.0-alpha1`, built from upstream release tag
`v2.15.0-alpha1`, commit `907bc9adf24dbd238d72c08127827e6481cc6fe7`.
Sonatype rejected the release because the organization's monthly release-count
limit was exhausted. Maven Central availability is therefore **not verified**.
The reported quota reset is 2026-12-01; a higher limit or exemption requires
Sonatype approval. A GitHub release alone does not establish Maven availability.

For local verification, an independent shared-object checkout of that exact tag
published `core`, `cypher`, and `sootup` to an isolated temporary Maven repository.
The source build succeeded. Signing was disabled only for these temporary local
artifacts. A temporary Gradle init script resolves only these three exact
`io.johnsonlee.graphite:...:2.15.0-alpha1` coordinates from that repository.
The project's repositories and release signing configuration are unchanged.
This verifies source/API integration, not the eventual signed Central artifacts
or a clean consumer's ability to download the release.

Regression validation is in progress. No full-check, coverage-gate, or
observer-enabled performance acceptance is claimed by this interim record.
Earlier results below remain tied to their original versions.

## Historical environment

Date: 2026-11-02. Local environment: macOS, JDK 17. Graphite: 2.12.0.
Kotlin compiler and metadata library: 2.1.20.

## PR review: diagnostic provenance, JDK modules and observation scope (2026-11-02)

Frontend warnings now recognize the pinned Graphite diagnostic prefixes at callback
offset zero. An analyzed enum/string literal, including embedded newlines, cannot
fabricate a blocking warning; genuine method/control-flow skips, dependency
extraction failures and recovery messages remain recognized. Real Java/Kotlin
controlled/uncontrolled enum fixtures cover this through the engine.

JDK declarations use system-module readers from the running JDK image, including
`jdk.compiler`; they never borrow GraphFold's application/context classpath.
Actual javac Plugin, TaskListener and TreePathScanner inheritance resolves, while
an absent third-party superclass remains unresolved until explicitly supplied.
The same reader serves missing references and origin hierarchy checks without
expanding the safe-effects model for opaque calls.

Unrelated global observations no longer poison ordinary reachable method proofs.
Private callbacks without call edges, field/class and whole-program resource proofs
retain global observation. Private rename alignment, changed original anonymous
owner maps (including added/removed nested owners) and canonical initializer
changes hidden by field normalization also retain it. The normalization guard is
conservative across the snapshot pair; it does not claim complete heap/reflective
flow analysis. Sixteen real Java/Kotlin engine fixtures and seventeen snapshot
cases cover these positive and negative boundaries.

The focused run passed **192 JUnit tests**, zero failures/errors/skips, together
with type-aware engine production/test detekt and CLI installation. The unfiltered
root check and all unchanged 95% branch / 90% line gates are required separately.
This evidence is correctness validation, not performance acceptance.

The same real GraphFold self-analysis now has **109** blocking diagnostics rather
than 1,024: string-literal false warnings **22 → 0**, missing inherited JDK APIs
**6 → 0**, external object escapes **960 → 92**, dynamic access **20 → 1**;
the 16 fold hints remain. Both revisions select/match 23 sites and the diff-owned
finding remains. The remaining related/caller observations and fold hints still
make the final verdict FAIL with an UNVERIFIABLE_CHANGE; this is not a full proof
of self-analysis equivalence. Its actual JSON report validates against the schema.

See [diagnostic fixtures](../fixtures/frontend-diagnostics/README.md),
[observation fixtures](../fixtures/observation-scope/README.md) and
[self-analysis evidence](research/validation/observation-self-analysis-2026-11-02.json).

## PR review: Gradle project JAR variants (2026-11-02)

The adapter now excludes an exported project artifact from graph dependencies only
when its nonempty class inventory exactly matches merged raw application classes,
including paths and bytes. External archives, transformed/unique classes and
unknown layouts remain dependencies. Production resources, manifests, class
placements and runtime ordering still consume the original inventory.

The real two-module fixture forces `LibraryElements.JAR` and verifies the exported
classpath/artifact overlap, the resulting graph dependencies and retained packaged
resources. Four engine cases cover folded/live source changes and folded/live
ResourceBundle keys, including resource-mode graph expansion. Twenty-three adapter
cases cover equivalent archives and conservative rejection boundaries, including
reserved `META-INF/`, `BOOT-INF/` and `WEB-INF/` class paths even when identical
paths and bytes also exist in merged classes. The focused run passes all
**33 tests** (28 new and five existing production-inventory tests),
with zero failures/errors/skips, plus `:engine:detektMain` and `:engine:detektTest`.
The unfiltered check and unchanged coverage hard gates are required separately.

The same isolated GraphFold self-analysis fixture now completes selection and
folding on **both revisions, 23 sites each**, with no duplicate identity,
`ANALYSIS_FAILED` or `UNVERIFIED_ORIGIN`. It returns the changed report-text line as
an `UNVERIFIABLE_CHANGE`. Overall verdict remains **FAIL** with `INCOMPLETE_GRAPH`
diagnostics, including unresolved compiler inherited APIs and dynamic access.
This establishes the project-JAR fix, not complete self-analysis equivalence.
These runs are correctness checks, not performance acceptance.

See [self-analysis evidence](research/validation/project-jar-self-analysis-2026-11-02.json)
and [fixture](../fixtures/project-jar/README.md). The preceding origin probe below
is retained as historical evidence of the duplicate-identity failure.

## PR review: generated declarations and origin-failure continuation (2026-11-02)

Compiler provenance distinguishes Java synthetic enum-switch holders, Kotlin callable/property
references, and library-inline synthetic implementations from source anonymous objects. The last
category uses completed compiler outputs, unique synthetic metadata and a verified enclosing
method; it does not invent source identities or align generated names/body hashes. Marker
corruption and incomplete emission remain blocking. K1/K2 provider tests compile and execute
real reference and stdlib examples; engine positive/negative fixtures verify graph consequences.

When provenance genuinely fails, selection and diff comparison continue from original JVM
identities with only private instrumentation removed. Blockers remain, no dependent obligation
is credited as proved, and raw identity differences are explicitly unverified observations.
Java/Kotlin tests include unrelated ambiguous origins and folded anonymous renumbering.
The enum-switch negative fixture also exposed nested Java descriptor resolution; source,
directory and JAR tests compare signatures against actual javac outputs. JarTypeSolver's
existing literal-dollar nested-name limitation remains an explicit unresolved diagnostic.

The unfiltered root check and type-aware engine/gfc detekt passed **1,014 JUnit tests**,
with zero failures/errors/skips. All modules retain the **95% branch / 90% line** hard gates,
without new production exclusions.

| Module | JUnit tests | Line coverage | Branch coverage |
| --- | ---: | ---: | ---: |
| engine | 871 | 99.3543% | 95.8207% (4104/4283) |
| gfc | 86 | 98.6111% | 96.2810% (233/242) |
| cli | 28 | 98.6842% | 97.0588% (33/34) |
| gradle-plugin | 29 | 100.0000% | 100.0000% (18/18) |

All **132 Java + 209 Kotlin** corpus cases match their labels; all **341** actual
reports validate against the verdict JSON Schema. The same current provider binary
also passes the existing 20-compilation compatibility matrix with zero unresolved
origins and explicit unsupported-version rejection. These are correctness checks,
not representative multi-graph performance acceptance.

Real GraphFold multi-module self-analysis builds both revisions, reports zero
`UNVERIFIED_ORIGIN`, and reaches graph selection. It then encounters a separate,
pre-existing application-class/project-JAR overlap and fails with `Duplicate method
identity`. This is not an end-to-end success; artifact-aware dependency deduplication
remains required for that layout. The original review's local fixture SHAs were
unavailable, so this probe uses `ce33f05` plus an isolated one-line report-text change.

See [full-check evidence](research/validation/provenance-validation-2026-11-02.json),
[self-analysis evidence](research/validation/provenance-self-analysis-2026-11-02.json),
and [compiler matrix](research/compiler/verified-2026-11-02.log).

## PR review: scope folded-graph dataflow to resource analysis (2026-11-02)

The final folding loader enables interprocedural dataflow only for resource proofs;
both selection paths continue to enable it. With Graphite 2.12.0, these optional
edges do not change transformed bodies or callback dispatch. Two new compiled
regressions verify equal canonical bodies in ordinary/resource modes without
evaluating a general wrapper, and Cypher selection through a helper's return in
ordinary mode.

The following targeted rerun passed **52 tests**, with zero failures/errors/skips,
plus type-aware production and test detekt:

```sh
./gradlew :engine:test --tests '*GraphiteAnalyzerTest' --tests '*SelectionSafetyTest' \
  --tests '*OriginFoldSelectionTest' --tests '*DependencyResourceAnalysisTest' \
  --tests '*DependencyGraphProbeTest' :engine:detektMain :engine:detektTest --console=plain
```

This is correctness evidence, not performance acceptance or a replacement for the
unfiltered CI `check` and its unchanged coverage gates. The full-suite figures in
the next section describe the preceding dependency-resource implementation.

## PR review: folded dependency resource effects (2026-11-02)

The unfiltered root check, type-aware detekt, local publications and CLI distributions
passed with **930 JUnit tests**, zero failures/errors/skips. All four
modules retain the **95% branch / 90% line** hard gates without new production exclusions.

| Module | JUnit tests | Line coverage | Branch coverage |
| --- | ---: | ---: | ---: |
| gfc | 62 | 98.5294% | 95.4082% (187/196) |
| engine | 811 | 99.3604% | 95.7827% (4020/4197) |
| cli | 28 | 98.6842% | 97.0588% (33/34) |
| gradle-plugin | 29 | 100.0000% | 100.0000% (18/18) |

All **130 Java + 207 Kotlin** corpus cases match their labels;
all actual reports validate against the verdict JSON Schema. Eleven real Gradle
Kotlin + stdlib cases verify folded dependency effects with separate compile and
runtime-only JARs. Positive cases pass; surviving hardcoded/dynamic reads, callbacks,
initialization, unknown receiver behavior and unmodeled configuration injection fail.
Java regressions retain exposed static-field reads even without external dependencies
and keep a private-only uncalled type inactive. Inherited entry, field declaration,
replacement Intrinsics and unsupported provider/layout boundaries have dedicated tests.

Local publication/version smoke checks, 17 release-helper tests, public API documentation
and workflow lint pass. The required `run-unit-test` CI job aggregates both platform
verification jobs. No remote release or representative multi-graph performance acceptance
was performed. See [machine-readable evidence](research/validation/dependency-validation-2026-11-02.json),
[capability probe and integration boundaries](research/dependencies/README.md), and
[the design](design.md#61-非源码变更按实际生产产物证明).

## PR review: resource value calls and Class queries (2026-11-01)

The unfiltered root check, type-aware detekt, local publications and CLI distributions
passed with **835 JUnit tests**, zero failures/errors/skips. Every module
retains the **95% branch / 90% line** hard gates without new production exclusions.

| Module | JUnit tests | Line coverage | Branch coverage |
| --- | ---: | ---: | ---: |
| gfc | 62 | 98.5294% | 95.4082% (187/196) |
| engine | 716 | 99.3530% | 95.9047% (3583/3736) |
| cli | 28 | 98.6842% | 97.0588% (33/34) |
| gradle-plugin | 29 | 100.0000% | 100.0000% (18/18) |

All **130 Java + 207 Kotlin** corpus cases match their labels, and
all 337 actual reports validate against the verdict JSON Schema.
Real Gradle regressions prove folded-only properties keys remain safe with standard
output and returned string concatenation; live-key edits still fail. Java/Kotlin IR
and direct model tests cover scalar operations and unknown callbacks, streams,
loaders, bootstrap variants and receiver provenance. Compiled Class queries retain
member/loader reflection gaps and normalized runtime-name observation boundaries.

Local publication verification, ZIP/TAR version smoke checks, 17 Python release tests
and documentation checks for 319 public Kotlin declarations passed. No remote
release or representative multi-graph performance acceptance was performed.
See [value-call evidence](research/validation/value-call-validation-2026-11-01.json)
and the [resource model boundary](design.md#61-非源码变更按实际生产产物证明).

## PR review: inline copies, resource readers and scoped gaps (2026-11-01)

The unfiltered root check, type-aware detekt, local publications and CLI distributions
passed with **825 JUnit tests**, zero failures/errors/skips. Every module
retains the **95% branch / 90% line** hard gates without production exclusions.

| Module | JUnit tests | Line coverage | Branch coverage |
| --- | ---: | ---: | ---: |
| gfc | 62 | 98.5294% | 95.4082% (187/196) |
| engine | 706 | 99.3427% | 95.8639% (3523/3675) |
| cli | 28 | 98.6842% | 97.0588% (33/34) |
| gradle-plugin | 29 | 100.0000% | 100.0000% (18/18) |

The corpus contains **130 Java + 207 Kotlin** cases with zero mismatches;
all 337 actual reports validate against the JSON Schema. The 21 new inline
cases verify uncontrolled, folded and dead copies, including object members and
file-facade functions. Properties/accessors add parity coverage rather than claiming
the original private-function bug affected every property shape.

Resource tests verify direct constant bundle/key readers, folded-only new keys,
live changed values, unknown platform readers, escaping bundles, literal package
paths and external consumers. Mixed resource/source PRs retain source evidence.
Method-gap regressions cover cross-owner private state, private-member alignment,
immutable value factories, scalar logging and unbounded observation. Real Gradle
builds distinguish documentation/test-fixture variants from production features.
Whole-program shared-closure tests establish correctness only; no representative
multi-graph performance acceptance is claimed.

Local artifact verification, ZIP/TAR version smoke tests and 17 Python release tests
passed. No remote release was performed. See [review evidence](research/validation/review-closure-validation-2026-11-01.json)
and [capability boundaries](design.md#17-高精度检测的能力边界gap-与-trade-off).
Earlier records below are historical baselines.

## Production scope from actual build outputs (2026-11-01)

The unfiltered root check and type-aware detekt checks passed with **742 JUnit tests**,
zero failures/errors/skips. All four modules retain the **95% branch / 90% line**
hard gates, without production exclusions.

| Module | JUnit tests | Line coverage | Branch coverage |
| --- | ---: | ---: | ---: |
| gfc | 62 | 98.5294% | 95.4082% (187/196) |
| engine | 623 | 99.3435% | 95.6293% (3085/3226) |
| cli | 28 | 98.6842% | 97.0588% (33/34) |
| gradle-plugin | 29 | 100.0000% | 100.0000% (18/18) |

Actual Gradle builds verify unchanged README/CI/unpackaged translation inputs,
packaged resources, resource deletion, runtime-only generated dependencies, README
code generation, shared-class packaging exclusions, and mixed gated/uncontrolled
source changes. Kotlin-only README changes pass even with unmatched selectors after
validating provenance and removing only GraphFold's source marker; damaged origin
records block that shortcut. Class instructions, user annotations and string values
remain significant.

Real Maven builds verify runtime-only/provided separation, unchanged documentation,
and removal of an owned resource from classes and JAR while retaining the class
modification time. Unit regressions cover runtime grouping/order, archive metadata,
linked resource directories, ownership failures, unavailable build scope, and
whole-program comparison. These are correctness checks, not performance acceptance.
No representative multi-graph performance claim is made.

The Java 130 / Kotlin 186 corpus remains unchanged with zero mismatches; all 316
serialized reports validate against the JSON Schema. Local publication and CLI
ZIP/TAR version smoke checks pass, along with 17 release-helper tests. No remote
release was performed. See [production-scope evidence](research/validation/production-scope-validation-2026-11-01.json)
and the supported scope/tradeoffs in [design section 6.1](design.md#61-非源码变更按实际生产产物证明).
Earlier validation sections below are historical baselines.

## PR review: audit logging and compile-only SPI annotations (2026-11-01)

The full root check and type-aware detekt checks passed with **677 JUnit tests**,
zero failures/errors/skips, and the unchanged **95% branch / 90% line** hard gates.

| Module | JUnit tests | Line coverage | Branch coverage |
| --- | ---: | ---: | ---: |
| gfc | 62 | 98.5294% | 95.4082% (187/196) |
| engine | 558 | 99.2426% | 95.5533% (2858/2991) |
| cli | 28 | 98.6842% | 97.0588% (33/34) |
| gradle-plugin | 29 | 100.0000% | 100.0000% (18/18) |

The initial full run caught the new Gradle INFO branch missing from in-process coverage
(17/18 branches); an added worker assertion covers that path without lowering the gate.
The final root check reruns the changed plugin suite and reuses unchanged module results.

Regression tests cover broad selectors, unavailable versus zero counts, all four CLI
format/verbosity combinations, and Gradle default/info logging with configuration-cache
reuse. Default text and logs retain summaries; JSON and opt-in detailed logs retain
all selected nodes. A 10,000-node reporting fixture verifies bounded default output;
this is a correctness check, not performance acceptance.

Local publication validation and ZIP/TAR smoke checks passed. Engine and gfc publish
no AutoService annotations dependency, CLI distributions omit that JAR, and all four
generated SPI registrations remain present. No remote release was performed.
See the [machine-readable record](research/validation/review-logging-validation-2026-11-01.json)
for the exact command and counters. Earlier sections below are historical baselines.

## Standalone gfc module and generated SPI (2026-10-31)

The full root check, type-aware detekt checks, CLI distributions and local publication
completed in 21m 42s. The final gfc ServiceLoader and mixed-source Gradle Worker tests were then verified
with their complete module checks. All **671 JUnit tests passed**, with zero failures,
errors or skips. Each of the four modules passed the unchanged **95% branch / 90% line**
hard gates, without production exclusions.

| Module | JUnit tests | Line coverage | Branch coverage |
| --- | ---: | ---: | ---: |
| gfc | 62 | 98.4940% | 95.4082% (187/196) |
| engine | 556 | 99.2024% | 95.5474% (2854/2987) |
| cli | 24 | 98.5915% | 96.6667% (29/30) |
| gradle-plugin | 29 | 100.0000% | 100.0000% (16/16) |

The published engine POM requires the same-version gfc artifact. Local publication
validation checks all three generated compiler SPI registrations; ZIP/TAR smoke checks
verify the matching gfc JAR and CLI version. Seventeen Python release tests passed.
The javac provider and protocol load through an isolated JDK-only ServiceLoader.

Java 130 and Kotlin 186 corpus cases remain correct, and all 316 actual reports pass
the JSON Schema. Existing real mixed-language Gradle/Maven compilation and incremental
reuse tests run against the independent provider JAR. The actual gfc artifact also
passed the [20-run Kotlin compiler matrix](research/compiler/verified-gfc-2026-10-31.log),
including explicit rejection of Kotlin 1.9.0. These are correctness/compatibility checks,
not representative multi-graph performance acceptance. No remote release was performed.
The [previous enum validation](research/validation/enum-validation-before-gfc-2026-10-31.json)
remains available as historical evidence.

## Typed statuses and codes: full quality gate (2026-10-31)

The unfiltered command `./gradlew check :engine:detektMain :engine:detektTest :engine:detektTestFixtures :cli:installDist --continue --console=plain` passed in 19m 55s.
All **667 JUnit tests passed**, with zero failures, errors or skips. The unchanged
per-module **95% branch / 90% line hard gates** passed without production exclusions.
These are full-suite counters, not merged targeted runs.

| Module | JUnit tests | Line coverage | Branch coverage |
| --- | ---: | ---: | ---: |
| engine | 615 | 99.1143% | 95.4588% (3048/3193) |
| cli | 24 | 98.5915% | 96.6667% (29/30) |
| gradle-plugin | 28 | 100.0000% | 100.0000% (16/16) |

Regression coverage includes enum-backed diagnostic and Git change codes, separate
rename similarity, strict typed Gradle export records, CLI format conversion, all nine
report enum/schema contracts, and compiler-origin protocol validation. The provider's
shared enums load in a JDK-only isolated classloader. Java 130 and Kotlin 186 corpus
cases remain correct, and all 316 actual reports pass the JSON Schema.

The current provider passed the [20-run Kotlin compiler matrix](research/compiler/verified-enums-2026-10-31.log),
including K1/K2 and four physical encodings; unsupported 1.9.0 was rejected.
This is correctness and compiler-hook compatibility evidence, not representative
multi-graph performance acceptance. The previous 657-test result is preserved in
[its historical record](research/validation/fold-audit-validation-before-enums-2026-10-31.json).

## PR review: capacity, version and fold audit quality gate (2026-10-31)

The unfiltered command passed in 20m 51s:

```sh
./gradlew check :engine:detektMain :engine:detektTest :engine:detektTestFixtures :cli:installDist --continue --console=plain
```

All **657 JUnit tests passed**, with zero failures, errors or skipped tests. Every
module passed the unchanged **95% branch / 90% line** hard gates without production
exclusions. These counters come from the complete suites accepted by this command,
not merged filtered runs.

| Module | JUnit tests | Line coverage | Branch coverage |
| --- | ---: | ---: | ---: |
| engine | 606 | 99.1064% | 95.4445% (3038/3183) |
| cli | 23 | 98.5075% | 96.6667% (29/30) |
| gradle-plugin | 28 | 100.0000% | 100.0000% (14/14) |

The prior 603-test review baseline is preserved in
[its historical record](research/validation/review-validation-before-capacity-2026-10-31.json).
New regressions cover strict YAML document/structure validation, raw selection audits,
separate selected/matched counts, zero-match blocking, query failures, skipped selection,
CLI and Gradle log/report agreement, and valid/invalid JSON Schema payloads.
All 316 actual Java/Kotlin corpus reports also passed schema validation.
They also cover slot selection by exact pair and bounded head-history distance,
lock cleanup and fallback, packaged CLI version consistency, the derived 25-minute
default, explicit overrides, CLI and Gradle option validation and propagation, additive environment names, real held-slot
contention and recovery, and retryable CLI exit code 3 with other error precedence.
The lease timeout measures slot acquisition only. It does not bound checkout,
inactivity, or the total analysis duration. The historical CLI release validation records
have been removed with the CLI; current publication verification covers engine, GFC and the native plugins.

## PR review regressions (2026-10-31)

The review corrections preserve the 95% per-module branch-coverage hard gate and normal
incremental compilation. Regression coverage includes:

| Area | Regression evidence |
| --- | --- |
| Checkout containment | Parent symlink replacement and case-insensitive `.git` component rejection |
| Revision resolution | A full-SHA local import excludes unrelated branch objects; deterministic abbreviated-object collisions and non-commit revisions have explicit diagnostics |
| Workspace retention | Bounded slots, interruptible wait, timeout, released-slot reuse and aliased cache paths |
| Kotlin Gradle hook | Real KGP `compilerOptions` contains the provider, output/run identity and inline-source arguments |
| Empty diff lines | Single blank-line insertion/deletion has a zero-width source range and an end-to-end PASS |
| Kotlin module ownership | Same-package, same-filename sources in separate compiled modules cannot borrow each other's owners |
| Graph comparison | Owner indexes preserve class initialization, constructed callbacks, unreachable-owner behavior and repeated queries |
| Rename alignment | A 24-helper dependency chain aligns to a fixed point; ambiguous leaves and mutual recursion remain unpaired |
| Temporary cleanup | I/O, lazy traversal and access failures are logged without replacing the analysis result |
| Build diagnostics | Bounded output survives log deletion; long lines and unreadable logs have deterministic handling |
| Build environment | Non-allowlisted variables are absent in the subprocess; explicit SDK opt-in restores only selected names |
| Maven recovery | POM version and compiler-target changes rebuild automatically, then resume incremental reuse; interrupted state recovers |
| Maven reactor | Active-profile modules build; unrelated fixture/example POMs and source roots are excluded |
| Maven test dependencies | A sibling test-jar reactor compiles without executing tests |
| Maven test provenance | Main/test classes and anonymous owners may have identical FQCNs; a test-only edit cannot replace the production source hash or bytecode |
| Shared fixture support | CLI and Gradle plugin execute against the same engine test fixture; fixture variants are excluded from publications |

Publication verification has independent evidence. Seven Python helper tests cover version,
asset and publication-inventory validation. `verify-publication-gate.py` creates a disposable
signing key and an isolated local repository named `sonatype`: matching signed publications
are accepted, GPG verifies every uploaded payload, and modified JAR/POM/module files are
rejected before the implementation publication uploads. This regression runs in release
preparation and never publishes remotely.



## Incremental builds and object flow: previous baseline (2026-10-31)

The standard, unfiltered command passed in 19m 17s:

```sh
./gradlew check :engine:detektTest :cli:installDist --console=plain
```

All **581 JUnit tests passed**, with zero failures, errors or skipped tests. Each module
passed the unchanged **95% branch / 90% line** hard gate, with no production-class
exclusions. Counters are from this single unfiltered run, not merged development runs.

| Module | JUnit tests | Line coverage | Branch coverage |
| --- | ---: | ---: | ---: |
| engine | 559 | 99.1664% | 95.3504% (2871/3011) |
| cli | 9 | 97.4359% | 100.0000% (14/14) |
| gradle-plugin | 13 | 100.0000% | 100.0000% (4/4) |

Both corpora were unfiltered: **130 Java + 186 Kotlin cases**, with no false positives or
false negatives. Existing anonymous-origin and physical-encoding regressions also passed.
The added object-rendering fixtures cover 36 Java and 28 Kotlin methods (21 safe and 43
blocked); blocked diagnostics include analysis gaps and are not counted as confirmed
uncontrolled violations. The suite includes real Gradle/Maven repeated-build, edit,
source-deletion, inline-source-position and cache-restoration checks described below,
plus 36 Git-adapter tests covering persistent leases and checkout correctness.

The current provider passed the [20-run Kotlin matrix](research/compiler/README.md)
with 15 origin records and 16 verified compiler-owned outputs per compilation. Installed
CLI help confirms `--repository`; all six Python release-helper tests passed. Provider SPI
packaging and `git diff --check` passed. No production or test source changed after the
full run; only validation records and PR documentation were updated.

These are correctness gates, not representative multi-graph performance acceptance.
The current [machine-readable summary](research/validation/main-validation.json) records the exact
counters; the earlier [anonymous-origin result](research/validation/anonymous-origin-validation-2026-10-30.json)
is preserved separately.

## JGit migration verification

The runtime Git adapter now uses JGit 7.8.0.202609011348-r for fetching,
revision resolution, shared-object checkouts and tree diffs. It does not invoke the
Git executable. Base/head checkouts have separate metadata and indexes backed by
one bare object cache through alternates; these are not registered native linked
worktrees. HTTP/SSH authentication integration remains unverified.

The following targeted command passed on 2026-10-30:

```sh
./gradlew :engine:test --tests '*Git*Test' :engine:detekt :engine:detektTest
```

All 26 Git-adapter tests passed in the unfiltered engine run. They cover exact
revisions, source checkout preservation, concurrent isolation, object sharing,
symlinks/executable modes, additions/deletions/renames, binary/empty files, cache
refresh/recovery, unreachable commits, cancellation, cleanup, rejection of `.git`
metadata paths, and explicit rejection of unsupported LFS/submodule inputs.
The child-JVM test uses an empty executable search path, constructs its fixtures
with JGit, and verifies checkout, diff, cleanup and a complete Java engine analysis
with an expected finding and no blocking diagnostics. A loopback JGit daemon
verifies real `git://` initial fetch and cache refresh, with no native server process.
Failure-path tests verify credential redaction, cancellation and unmodified JVM
Error propagation, including an Error raised while the thread is interrupted.
A JDK loopback HTTP server verifies unauthenticated dumb HTTP fetch, exact revisions,
diff and cache refresh. Smart HTTP and HTTP/SSH authentication remain unverified.

## Persistent checkout lease correctness (2026-10-31)

The initial focused command passed with 32 tests and no failures:

```sh
./gradlew :engine:test --tests 'io.johnsonlee.graphfold.engine.git.*' --console=plain
```

Six new persistent-workspace tests verify stable base/head paths across changed revisions,
unchanged source timestamps, retained `.gradle/` and `build/` contents, restoration of
modified tracked sources, removal of deleted tracked and nonignored untracked files,
changes to ignore rules, symlink/directory and executable-mode transitions, and protection
against a replaced parent symlink redirecting deletion outside the private checkout.
Lease tests cover simultaneous JVM reservations, closing from another thread, skipping an
actual child-process file lock, and reuse after release. Existing failure tests also reopen
a slot after LFS/submodule rejection. HTTP, Git protocol and empty-`PATH` tests now assert
that close retains the private checkouts instead of deleting them.

The final unfiltered run includes 36 Git tests, adding symlink timestamp/target restoration,
damaged lock-path recovery, overlapping-lock recovery, and malformed tree traversal rejection.

These checks validate persistent build-state availability, not a large-repository performance
claim. Concurrent peak usage determines the number of retained slots; automatic eviction and
disk quotas are not implemented. OS file locks are released on process termination, but this
suite does not certify transaction recovery at every possible interrupted JGit write.

## Previous anonymous-origin integration verification (2026-10-30)

The standard, unfiltered command passed in 18m 22s:

```sh
./gradlew check :engine:detektTest :cli:installDist --console=plain
```

All 519 JUnit tests passed, with no failures, errors or skipped tests. Every module
passed its unchanged **95% branch / 90% line** hard gate, with no production class
exclusions. These results come from the final single unfiltered run, not merged
coverage from development iterations. Installed CLI help confirms `--repository`;
all six release-helper tests and `git diff --check` also passed.

| Module | JUnit tests | Line coverage | Branch coverage |
| --- | ---: | ---: | ---: |
| engine | 497 | 99.1107% | 95.2293% (2575/2704) |
| cli | 9 | 97.4359% | 100.0000% (14/14) |
| gradle-plugin | 13 | 100.0000% | 100.0000% (4/4) |

Both detection corpora were unfiltered: Java 130 cases and Kotlin 186 cases, with
zero false positives, false negatives or blocking diagnostics. Analysis gaps are
not accepted as true-positive evidence. These are labelled regression fixtures,
not a production-wide accuracy estimate. The historical machine-readable
[validation summary](research/validation/anonymous-origin-validation-2026-10-30.json) records the counters.

The run includes all 32 anonymous-origin pairs (14 FAIL / 18 PASS), four additional
CRLF/BOM full-engine variants, and the previous 22 generated-source-location cases.
The encoding variants reuse controlled/uncontrolled same-line Kotlin fixtures and
assert the original verdicts and exact diff line/column ranges. Source-origin
ambiguity is separately verified to block without inventing source findings.
Actual-runtime regressions also demonstrate anonymous renumbering through stack
reads and escaped standard/custom exceptions, then require normalization to block.
Method-reference rendering, scalar negative cases, sidecar corruption, inline
copies, missing debug information and actual Gradle/Maven compilation are covered.

The same-binary [Kotlin provenance matrix](research/compiler/README.md)
passed 20 real compilations: five compiler/frontend combinations times LF, CRLF,
CR and BOM+CRLF. Every run verified 15 physical source records and zero unresolved
records, including astral Unicode, tabs and inline copies. Validation found and
fixed the compiler's CRLF/BOM coordinate conversion; PSI input preparation now
preserves physical UTF-16 offsets too. This is source-provenance compatibility,
not general equivalence between compiler lowerings. Metadata normalization remains
limited to schemas through 2.1; successful 2.2.10 provenance does not establish
full-engine 2.2 support.

Compiler-provider packaging was independently inspected: the current provider is
embedded as an isolated JAR, and compiler SPI registrations do not leak into the
main engine service-discovery namespace. No new publication rehearsal or hosted
CI run was performed for this integration.

## Previous integration and quality verification (before anonymous origins)

The final standard, unfiltered command passed:

```sh
./gradlew check :engine:detektTest :cli:installDist --console=plain
```

All modules passed their configured coverage verification tasks, with no failed or
skipped JUnit tests. The installed CLI help was also verified, including
`--repository`; all six Python release-helper tests passed.

| Module | JUnit tests | Line coverage | Branch coverage |
| --- | ---: | ---: | ---: |
| engine | 282 | 99.1448% | 95.2067% (1589/1669) |
| cli | 9 | 97.4359% | 100.0000% |
| gradle-plugin | 13 | 100.0000% | 100.0000% |

The 316 detection scenarios run inside two engine JUnit tests: Java 130
(60 positive, 70 negative), Kotlin 186 (84 positive, 102 negative). Both corpora
were unfiltered, with zero false positives, false negatives or blocking diagnostics.
The machine-readable [previous validation summary](research/validation/main-validation-before-anonymous-origins.json)
records the actual test counts, coverage counters and corpus metrics.

An earlier full command exposed an issue in a newly added plugin test harness
(property loading in ProjectBuilder). The corrected harness uses real mutable
Gradle providers; TestKit continues to verify actual property loading. The final
standard check above includes that fix.

The additional source-location run passed all 22 generated-code cases (Java 8,
Kotlin 14), plus seven Java source-index tests. It verifies base/head changed
character coordinates and actual emitted lambda, bridge, callback, default-parameter,
accessor, inline and suspend structures. Kotlin positive and negative suspend
fixtures also verify actual suspension followed by explicit continuation resumption.
These 22 cases are separate from the 316-case detection corpus. The first location
run exposed an incorrect fixture assertion: Kotlin 2.1.20 indy lambda implementation
methods do not require ACC_SYNTHETIC. The corrected test verifies the bootstrap
handle, actual method descriptor and capture parameter types instead; verdict and
location assertions were preserved.

The final unfiltered `check` includes all added tests. Each module's branch minimum
remains **95%**, line minimum 90%, with no production class exclusions. The gate is
wired into `check` and the CI build/publish workflows; it is not merely a reported metric.

The isolated headless UAST candidate and its separate results are described in
[the UAST evaluation](research/uast/evaluation.md). UAST is not adopted; these results are historical research, not an outstanding migration gate.

## Earlier executed gates (before JGit migration)

```sh
./gradlew detekt :engine:detektTest
./gradlew check :cli:installDist
cli/build/install/graphfold/bin/graphfold --help
python3 -m unittest discover -s .github/scripts -p 'test_*.py'
git diff --check
```

The following results are historical, not validation of the current tree.
All passed at that revision. The unfiltered full Gradle check completed in 12m 25s. No remote
publication or hosted Actions run was performed.

| Module | JUnit tests | Line coverage | Branch coverage |
| --- | ---: | ---: | ---: |
| engine | 176 | 98.72% | 90.62% |
| cli | 9 | 97.44% | 100.00% |
| gradle-plugin | 7 | 100.00% | 100.00% |

There are also six Python release-helper tests. JaCoCo gates apply to all production
classes in each module; no classes were excluded to meet the 90% line/branch gates.
The corpus runs within two JUnit tests, so its scenario count is separate from the
JUnit test count above.

## Earlier detection evidence (before JGit migration)

The actual Git → detached worktrees → compile → Cypher → fold → IR compare pipeline
passed every labelled scenario in both unfiltered corpora:

| Corpus | Cases | True positives | True negatives | False positives | False negatives |
| --- | ---: | ---: | ---: | ---: | ---: |
| Java | 118 | 60 | 58 | 0 | 0 |
| Kotlin | 166 | 84 | 82 | 0 | 0 |

Precision and recall are both 100% on each checked-in regression corpus, with no
blocking diagnostics. Operational failures are not accepted as true-positive evidence.
Raw results and metrics are generated under
`engine/build/reports/detection/{java,kotlin}/`; both metrics files record
`filtered: false`. Each scenario has its own expected verdict assertion.

The [fixture inventory](../fixtures/README.md) and [Java matrix](../fixtures/java-matrix.md)
list checked-in inputs and test entry points. Kotlin includes every Java counterpart
and 48 additional language-specific scenarios. Coverage includes paired Stream,
lambda/callback and control-flow changes, fields, private-member renames, new-code
reachability, static initializers, exceptions, and inherited overrides.

Additional passing integration suites load checked-in fixtures for six moves, four
actual file additions, four default-interface initialization cases, five reason
expectations reusing corpus cases, and two reflective-dependency boundary cases.
The newly activated interface-initialization tests also execute both revisions and
verify the changed runtime output. Reflection-boundary tests similarly verify runtime
field-name visibility, then require a retained difference or a blocking diagnostic;
they do not inflate corpus detection metrics.

Exact changed-character ranges are asserted for six uncontrolled Java Stream cases,
field-change reasons, moves with behavior changes, and interface initialization.
Pure moves record paths without inventing changed-character locations. Other tests
cover source mapping, worktree cleanup, mixed Kotlin/Java compilation, real Gradle/Maven
builds, CLI parity, and Gradle configuration-cache reuse.

The full corpus previously exposed a Kotlin false positive: indentation outside an
added, uncalled method created an extra class obligation. ProofPlanner now ignores
whitespace-only uncovered fragments while retaining actual class-header changes;
Java and Kotlin unit regressions and the final full corpus passed after the fix.

These are development fixtures, not an independent production sample. They do not
establish 90% precision/recall across real repositories. See design section 18 and the
fixture inventory for unsupported semantics and conservative analysis boundaries.

## Earlier release rehearsal

Before this expanded fixture follow-up, local release staging, archive extraction,
artifact verification and CLI exit-code smoke tests passed for `0.1.0-local.3` using
`publishAllPublicationsToReleaseVerificationRepository`, `release.py verify`, and
`release.py smoke`. That rehearsal predates the Kotlin/fixture expansion. The current
run rebuilt the installed CLI and verified help; it did not repeat publication staging
or publish remotely.


## Incremental Gradle compiler provenance

The analysis adapter no longer disables Java/Kotlin incremental compilation or Gradle
build caching. Compile tasks declare task-local provenance directories as outputs;
a stable provider digest replaces per-analysis random IDs for these tasks. Successful
incremental emission overlays fresh owner records, keeps unchanged source/class hashes,
and drops removed class owners. The exported snapshot is validated against actual class
bytes as well as source bytes. A compiler failure never publishes a successful snapshot.

The mixed Java/Kotlin multi-module correctness fixture exercises unchanged `UP-TO-DATE`
tasks, Java and Kotlin edits, retention of an unchanged Kotlin anonymous object, deletion
of its source file, and `FROM-CACHE` restoration after deleting build outputs.
Cross-file and cross-project inline object regressions insert a newline before each
definition: the inline-source compiler input refreshes the affected compilation while
an unrelated Kotlin module remains `UP-TO-DATE`. The inline-source digest is scoped to own sources
and the transitive closure of each reached project’s own compile dependencies, using conservative
file-level token matching. The regression includes `app → library → implementation dependency`: the
last project is absent from app’s compile classpath but its inline source is present in app’s emitted
origin records, and a whitespace-only edit there refreshes the copied provenance.
The persisted provider JAR is also deliberately corrupted and repaired from the trusted
embedded artifact without invalidating otherwise unchanged compile tasks. The Kotlin
edit additionally asserts that the compiler reports incremental compilation and that
an unchanged anonymous class retains its output timestamp, proving it was not re-emitted. A separate
tampering test changes class bytes while preserving the cached manifest and requires a
blocking class-hash diagnostic.

These are correctness checks, not performance acceptance measurements. Absolute compiler
argument paths currently partition caches by checkout location; cross-machine or relocated
cache reuse is not claimed. Gradle configuration cache remains disabled for the injected
export init script; this does not disable incremental compilation or the task build cache.


## Incremental Maven compiler provenance

Real Maven 3.9.11 fixtures exercise the compiler adapters without `clean` or a forced
`useIncrementalCompilation=false`. The Java reactor retains unchanged class timestamps,
then validates a source edit and removal of the final anonymous-object source. The Kotlin
2.1.20 fixture explicitly enables incremental compilation, preserves an unchanged class
across edits, and removes all Kotlin sources while retaining a Java class in the shared
output directory. Compiler output ownership for a cross-file inline copy names the caller
file, while its origin record names the definition file; the fixture requires an actual
copied class rather than assuming every inline object is copied. A newline-only definition
change must refresh that copied origin’s source hash and advance its physical offset by one.

Successful builds retain and merge source/class-bound provenance. A separate compiler
output inventory covers named, anonymous and synthetic classes; unowned outputs block.
Removal is authorized only by verified prior ownership and unchanged class hashes,
including the corresponding Kotlin incremental class cache. Unit cases cover malformed
manifests, unknown and duplicate outputs, partial deletion of multi-source outputs, source
and class tampering, invalid paths and symlink escapes. No basename or generated-number
heuristic authorizes deletion.

Maven effective compiler configuration or provider changes invalidate reactor target outputs
and rebuild automatically; incomplete builds also recover this way. Subsequent unchanged
builds resume incremental reuse. The original blocking behavior was replaced during PR review. Kotlin Maven’s native incremental compiler refreshes the tested 2.1.20 cross-file inline
case; this does not establish every compiler/dependency combination, and unrefreshed copies
fail source/hash validation. These remain stated limits, not successful analysis results. Cross-machine cache reuse and large-repository performance are not claimed.


## Object rendering flow regression scope

`OriginObjectFlowTest` compiles the checked-in Java/Kotlin fixtures with real compilers
and checks per-method observability diagnostics. The fixtures distinguish safe local
allocations/aliases from renamed or unknown objects, including merges, loops, casts,
nullable receivers, inherited rendering, overridden hashCode, method references,
reference containers, builders and formatting. Runtime examples execute both revisions
and observe changed anonymous names through direct rendering and escaped null-pointer
exception stacks. Boundary tests cover incomplete frames and bootstrap constants.

The analysis is intraprocedural and does not claim a general heap or interprocedural
points-to model. Mutable builders, formatting and console dispatch retain conservative
guards. Both tested compilers emit invokedynamic for the wide-concat fixture and its safe
operands can be discharged. A separate explicit Kotlin builder-chain fixture remains
blocking; the distinction follows emitted bytecode rather than source language. Blocking diagnostics are not counted as
confirmed uncontrolled violations or successful detection evidence.


## Folded origin-name observation scope

The repository-wide origin observation rejection now emits method-local evidence and
checks it against each revision's folded reachability. Current-thread captures also
require a potentially executing renamed frame. Existing/other-thread stack readers
and deferred method references remain conservative; Serializable ancestry remains a
type-level normalization guard. Missing normalized method declarations fail closed.

`fixtures/origin-observation/` contains 20 real Java/Kotlin base/head pairs. These cover
uncalled private observers, unrelated exception construction, folded-away rendering,
string concatenation and method references, surviving rendering, transitive exception
capture and cross-thread stack observation. Six additional compiler checks verify
actual anonymous-owner renumbering and dynamic factory emission. A normalized bytecode
integration test verifies owner/array-descriptor remapping and inherited stack getters;
frame-scope tests prohibit combining base/head edges into a fictitious call stack.

Targeted validation passed 42 tests and both engine type-aware detekt tasks:

```sh
./gradlew :engine:test --tests '*OriginObservation*Test' \
  --tests '*OriginFoldSelectionTest' --tests '*ObservedFrameScopeTest' \
  :engine:detektMain :engine:detektTest
```

This focused run is correctness evidence, not performance acceptance or a replacement
for the unfiltered `check` and per-module 95% branch / 90% line coverage gates.


## Independent publication channels (2026-11-10)

The GraphFold release workflow now prepares and verifies one set of release
payloads before starting Maven Central, GitHub Packages, and GitHub Release as
independent jobs. Central is not a prerequisite of either GitHub channel. Failed
jobs retain their failure status, and an always-run summary records each result.
A shared preparation failure still blocks all publication; the change does not
bypass tests, coverage, payload integrity, or signing requirements for Central.

`actionlint .github/workflows/publish.yml` and all **17** release Python tests
passed. The actual Gradle fixture in
`.github/scripts/verify-publication-gate.py` also passed: matching signed Central
and unsigned GitHub Packages payloads were accepted by local stand-in
repositories, while changed JAR, POM, and Gradle module metadata were rejected
before upload for both channels. Test-fixture artifacts stayed excluded from
publication. The Packages channel requires no Sonatype or GPG secret.

Manual workflow dispatch runs the Packages publisher against a file repository
and compares the resulting payloads with the verified release. The summary
labels this as local rehearsal rather than successful remote publication. No
hosted workflow or actual GitHub Packages upload has been exercised in this
session; package permissions and remote availability remain unverified.

On 2026-11-11, the actual GraphFold modules and Gradle plugin marker also passed
a local Packages rehearsal: all four module publications and the marker were
staged, verified, uploaded through the same pre-publication guard to an isolated
file repository, then checked for complete payload equality. The rehearsal used
locally rebuilt Graphite alpha1 artifacts and does not verify Central availability.
Graphite publication is unchanged. Its unavailable alpha1 Central artifacts
remain a separate prerequisite for a hosted GraphFold preparation build.


## CI coverage reporting (2026-11-11)

The Build workflow now collects exact per-module LINE and BRANCH counters on
both Ubuntu and macOS. Missing reports, malformed counters, zero measured totals,
and any nonzero missed count fail the coverage step. Matrix fail-fast is disabled
so one platform failure does not cancel the other platform's report. Report
collection and artifact upload run after failures; the existing `run-unit-test`
required check still requires both verification jobs to succeed.

A separate trusted-default-branch `workflow_run` workflow resolves PR identity,
head SHA, run attempt, and platform job outcomes from GitHub APIs. It consumes only
bounded, schema-validated JSON archives, never executes downloaded PR content,
and never forwards its token to artifact storage redirects. Per-PR concurrency,
a stable marker, owned-bot checks, pagination, and duplicate cleanup maintain one
coverage comment. Stale heads, newer runs, and superseded attempts skip writes.
Authoritative run-to-PR associations take priority; runs without associations are
reported only when exactly one open PR matches, avoiding cross-base attribution
for two PRs with the same head. Malformed, encrypted, or unsupported ZIP data
becomes an incomplete report rather than preserving an earlier success silently.
Previous-attempt artifacts cannot fill a missing current platform report.

The reporting and existing release scripts currently pass **40 Python tests**.
Build and Publish pass actionlint. The reporter also passed before adding
`queue: max`; actionlint 1.7.12 (the latest release and current upstream parser)
rejects only this new concurrency key. GitHub's [current concurrency documentation](https://docs.github.com/en/actions/how-tos/write-workflows/choose-when-workflows-run/control-workflow-concurrency)
explicitly supports it. It prevents delayed older reports from replacing a newer
pending report. No lint suppression was added; full reporter lint remains pending
tool support. The collector also read the current real
JaCoCo XML files and correctly failed on their remaining missed counts. This
verifies parser behavior, not final coverage acceptance. No PR comment has been
posted and no hosted reporter has been exercised in this session; the reporting
workflow becomes available after merge into the default branch.
