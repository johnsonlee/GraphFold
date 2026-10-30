# GraphFold

Detect uncontrolled code changes through conditional folding.

GraphFold compares two Git commits under the same explicit folding assumptions. The diff establishes what must be proved; Graphite selects call sites with Cypher and folds JVM code; the engine compares the remaining representation and reports changes that could not be proved controlled. AB tests are one use case, not an engine-specific concept.

The gate is fail-closed: both surviving changes and analysis failures produce `FAIL`. Surviving structural differences are reported as unverified changes; build, Git, and incomplete-analysis errors are diagnostics rather than invented source locations.

See [the design](docs/design.md) for the proof model, gaps, and trade-offs, and [validation results](docs/validation.md) for the executed checks.

## Modules

| Module | Artifact / entry point |
| --- | --- |
| `engine` | `io.johnsonlee.graphfold:engine:<version>` |
| `cli` | JVM application distribution, executable `graphfold` |
| `gradle-plugin` | Plugin ID `io.johnsonlee.graphfold`; task `graphFoldCheck` |

The project targets JDK 17 and pins Graphite to `2.12.0`. Git operations use JGit `7.8.0.202609011348-r`; the Git executable is not a runtime requirement. A shared bare object cache supplies isolated base/head checkouts through Git object alternates, with separate `.git` metadata and detached HEADs. These are shared-object checkouts, not native linked worktrees. Files are materialized from commit blobs without checkout hooks or smudge filters; unsupported submodules and Git LFS inputs block analysis. Checkout metadata does not yet reproduce tags or remote refs; builds deriving versions from them require separate validation. HTTP/SSH authentication integration has not yet been verified; native credential-helper or SSH-agent inheritance is not guaranteed.

## Build and validation

```sh
./gradlew check
./gradlew :cli:installDist
cli/build/install/graphfold/bin/graphfold --help
```

`check` runs JUnit tests, detekt, and per-module JaCoCo verification requiring at least 90% line coverage and 95% branch coverage. No production classes are excluded from the coverage gates. Integration tests exercise real Git repositories, compilation, Graphite Cypher/folding, CLI execution, and the Gradle Worker API.

The manually labelled paired-source regression corpus is in [fixtures](fixtures). Its test records per-case results in `engine/build/reports/detection/{java,kotlin}/results.json` and checks precision and recall against the requested threshold. These are measurements on the checked-in corpus, not a claim of 90% accuracy on arbitrary production repositories. Operational failures are not accepted as successful detection evidence.

## Fold document

A rule contains a Cypher query selecting `CallSite` nodes, optional argument constraints, and a replacement value:

```yaml
version: 1
folds:
  - select: |
      MATCH (cs:CallSite {callee_class: "sample.Ab", callee_name: "enabled"})
      RETURN cs
    args:
      0: feature
    value: false
```

The same rules apply to both revisions. Multiple rules in one scenario apply together. Multiple explicitly enumerated scenarios can be supplied with a `scenarios` root; every scenario must pass:

```yaml
scenarios:
  - name: disabled
    folds:
      - select: |
          MATCH (cs:CallSite {callee_class: "sample.Flags", callee_name: "enabled"})
          RETURN cs
        value: false
```

Queries express caller-authorized assumptions; an overly broad rule is an overly broad assumption. GraphFold does not infer the intended feature ID from the source. The default scenario limit is 32; exceeding it fails rather than sampling scenarios.

## CLI

```sh
graphfold \
  --repository /path/to/repository \
  --base <base-sha> \
  --head <head-sha> \
  --fold rules.yml \
  --cache-dir ~/.cache/graphfold \
  --format json
```

`--repository` accepts a local path or a Git URL. `--fold` reads a YAML file. Reports go to stdout; `text` is the default format. Exit codes are `0` for PASS, `1` for a failed analysis gate, and `2` for input, Git, or build failures. Both nonzero outcomes block delivery.

## Engine SDK

```kotlin
dependencies {
    implementation("io.johnsonlee.graphfold:engine:<version>")
}
```

```kotlin
import io.johnsonlee.graphfold.engine.AnalysisReports
import io.johnsonlee.graphfold.engine.AnalysisRequest
import io.johnsonlee.graphfold.engine.EngineConfiguration
import io.johnsonlee.graphfold.engine.GraphFoldEngine
import java.nio.file.Files
import java.nio.file.Path

val engine = GraphFoldEngine(
    EngineConfiguration(cacheDirectory = Path.of("/path/to/cache")),
)
val result = engine.analyze(
    AnalysisRequest(
        repository = "/path/to/repository",
        base = baseSha,
        head = headSha,
        fold = Files.readString(Path.of("rules.yml")),
    ),
)
println(AnalysisReports.json(result))
```

The SDK accepts fold document contents, not a filename. Results include `verdict`, findings, diagnostics, analysis metadata, and `reasons` for both passing and failing obligations. A finding can have multiple locations. Locations identify BASE or HEAD, repository-relative paths, and one-based `line: {start, end}` / `column: {start, end}` coordinates. End positions are exclusive and columns count UTF-16 code units. Deletions can refer to BASE. Each reason separates AST change categories (such as `FIELD_INITIALIZER_CHANGED` or `METHOD_RENAMED`) from the proof outcome: `FOLDED_EQUIVALENT`, `UNREACHABLE`, or `SURVIVING_DIFFERENCE`. A rename label is a structural candidate, not an equivalence proof. Pure file moves record old/new paths without inventing changed character ranges.

## Gradle plugin

The release workflow publishes plugin markers to Maven Central. After a release is published, add it to plugin resolution:

```kotlin
// settings.gradle.kts
pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}
```

```kotlin
// build.gradle.kts
plugins {
    id("io.johnsonlee.graphfold") version "<version>"
}
```

```sh
./gradlew graphFoldCheck \
  -Pgraphfold.base=<base-sha> \
  -Pgraphfold.head=<head-sha> \
  -Pgraphfold.fold=rules.yml
```

The three required Gradle properties can also be set in `gradle.properties`. The repository defaults to the root project directory, and relative `graphfold.fold` paths resolve against that directory, including when the plugin is applied to a subproject. No `graphfold` extension is registered, and `graphFoldCheck` is invoked explicitly; it is not attached to `check`.

The task calls the engine in a process-isolated Gradle worker, writes `build/reports/graphfold/report.json` and `report.txt` in the applying project, then fails when the gate fails. It does not invoke the CLI. The task deliberately runs again instead of claiming up-to-date/build-cache safety for external Git and build inputs.

## Current capability boundary

- Java uses JavaParser; Kotlin uses headless Kotlin PSI and actual emitted JVM identities from ASM. Both produce UTF-16 source locations. UAST is not adopted; the [UAST evaluation](docs/uast-integration-evaluation.md) is retained as historical research, not a migration plan. Ambiguous Kotlin method/field mappings fall back to owner-level obligations; missing ownership blocks analysis.
- Anonymous objects use compiler-recorded source ranges and actual emitted owners, followed by unique source-context pairing. Generated numbering is never the pairing key. The [same-binary Kotlin probe](docs/experiments/kotlin-origin/README.md) verifies 1.9.20/K1, 2.0.21/K2, 2.1.20/K2, 2.2.10/K2, and 2.1.20 with language 1.9/K1. This verifies provenance, not general equivalence between compiler lowerings; metadata normalization is limited to schemas through 2.1. Ambiguous copies, unsupported versions, and observable runtime-name changes block analysis.
- Plain Java/Kotlin and mixed-source compilation, Gradle-wrapper, and Maven adapters are available. The embedded Kotlin compiler is pinned to 2.1.20. Custom source sets, compiler plugins, generators, Kotlin Multiplatform, and dependency arrangements require separate validation.
- The diff determines which changes need proof. Existing externally callable methods seed the entry model; new methods/classes require surviving references or modeled external entries. A new public modifier alone does not establish execution. Callbacks, constructed objects, static field access, and class initialization participate in reachability.
- Narrow private-member alignment can prove consistent field/method renames when declaration, reference, and body checks agree. Added unused code and pure file moves can pass. Changed initializers, observable side effects, public API changes, and moves that alter JVM identities still require proof.
- Kotlin metadata is decoded and re-encoded with only private/private-to-this functions and properties removed from the contract projection. Public/internal/protected metadata remains significant, including parameter names, defaults, nullability, and inline flags. Unsupported metadata versions or malformed metadata block analysis; reflection is not covered by this private-name abstraction.
- Full graphs and folded Jimple are the comparison basis; AST categories only explain the source change. Unsupported folding, unmatched rules, missing dependencies, and unresolved source ownership block the gate. Arbitrary refactoring equivalence and context-sensitive shared-wrapper specialization are not general guarantees.
- Java/Kotlin reflection, native/JNI callbacks, serialization hooks, framework entry conventions, resources, and configuration require explicit models. Detected unsupported dynamic access blocks analysis; the implemented checks are not a complete inventory of every possible runtime entry mechanism.
- Opaque third-party calls currently accept only primitive values, strings, boxed primitives, and null without an object-escape model. Passing arrays, collections, `Optional`, application objects, or an unmodeled library receiver blocks analysis—even `List<String>` lacks a proven heap/element boundary. This diagnostic is an analysis gap, not a confirmed violation. Supported JDK/Kotlin callback paths are treated separately.
- Stream/lambda/callback and condition-control cases have paired controlled and uncontrolled fixtures. Java-equivalent Kotlin cases and Kotlin-specific features are included. The unfiltered validation passed all checked-in corpus cases; this does not establish accuracy across arbitrary production repositories. Refer to the validation record for executed evidence.

These limitations are not blanket exemptions. A surviving structural difference is reported as `UNVERIFIABLE_CHANGE`; an analysis gap is a blocking diagnostic. Neither is silently treated as a confirmed business counterexample or a successful proof.

## Release workflow

Tags matching `v<strict-semver>` trigger [Publish](.github/workflows/publish.yml). Every release first runs the quality gates, stages all Maven publications locally, verifies implementation JARs and the plugin marker, builds CLI ZIP/TAR distributions, and generates SHA-256 checksums. Tag builds then publish signed artifacts to Sonatype Central and attach verified distributions to a GitHub Release. Prerelease tags produce prereleases.

`workflow_dispatch` accepts a tag-shaped version and performs a dry run without remote publication. Artifacts remain downloadable from the workflow run. Local rehearsal:

```sh
./gradlew check publishAllPublicationsToReleaseVerificationRepository \
  :cli:installDist :cli:distZip :cli:distTar -Pversion=0.1.0-local.1
python3 .github/scripts/release.py verify build/repository 0.1.0-local.1
```

Remote publication requires the repository's Sonatype and signing secrets (`OSSRH_USERNAME`, `OSSRH_PASSWORD`, `OSSRH_PACKAGE_GROUP`, `GPG_KEY_ARMOR`, `GPG_KEY_ID`, and `GPG_PASSPHRASE`). Release preparation and publication are separate so missing credentials cannot be mistaken for a verified remote release.
