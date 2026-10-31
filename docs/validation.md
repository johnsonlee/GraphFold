# Validation record

Date: 2026-11-01. Local environment: macOS, JDK 17. Graphite: 2.12.0.
Kotlin compiler and metadata library: 2.1.20.


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
inactivity, or the total analysis duration. Nine Python release-helper tests passed,
including rejection of a missing or mismatched packaged CLI version. Release ZIP/TAR
version smoke checks and development SHA version checks also passed; their snapshot
and source-archive limitation are recorded in [version validation](research/release/cli-version-validation-2026-10-31.json). The earlier publication reproducibility and compiler compatibility
matrices below were not rerun for these changes.

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

Two clean, build-cache-disabled publication builds of the same review working-tree snapshot
at `0.1.0-review.20261031.1` produced 18 byte-identical JAR/POM/module payloads. Both passed
`release.py verify`; no test-fixtures artifacts or variants were published. Checksums are
recorded in [the rehearsal record](research/release/review-release-rebuild-2026-10-31.json).
This is local release verification, not evidence of remote publication or production
performance. No single-graph timing is used as performance acceptance.


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
