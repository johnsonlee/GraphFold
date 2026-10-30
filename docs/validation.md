# Validation record

Date: 2026-10-30. Local environment: macOS, JDK 17. Graphite: 2.12.0.
Kotlin compiler and metadata library: 2.1.20.

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

## Anonymous-origin integration and final quality verification

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
not a production-wide accuracy estimate. The current machine-readable
[validation summary](experiments/main-validation.json) records the counters.

The run includes all 32 anonymous-origin pairs (14 FAIL / 18 PASS), four additional
CRLF/BOM full-engine variants, and the previous 22 generated-source-location cases.
The encoding variants reuse controlled/uncontrolled same-line Kotlin fixtures and
assert the original verdicts and exact diff line/column ranges. Source-origin
ambiguity is separately verified to block without inventing source findings.
Actual-runtime regressions also demonstrate anonymous renumbering through stack
reads and escaped standard/custom exceptions, then require normalization to block.
Method-reference rendering, scalar negative cases, sidecar corruption, inline
copies, missing debug information and actual Gradle/Maven compilation are covered.

The same-binary [Kotlin provenance matrix](experiments/kotlin-origin/README.md)
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
The machine-readable [previous validation summary](experiments/main-validation-before-anonymous-origins.json)
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
[the UAST evaluation](uast-integration-evaluation.md). UAST is not adopted; these results are historical research, not an outstanding migration gate.

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
