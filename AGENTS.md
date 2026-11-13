# GraphFold agent instructions

## GraphFold scope and verdict semantics

GraphFold statically analyzes control coverage and safety evidence for production-code changes. It does not determine whether those changes actually execute or cause an observed runtime behavior difference.

- A production-code change without control coverage is an uncontrolled production change, even when the changed code is never executed.
- Uncontrolled does not mean mandatory rejection. An uncontrolled change may be accepted when sufficient static safety proof discharges the relevant obligations for the whole change. It must remain classified as uncontrolled; acceptance must cite the safety proof, not invented control coverage.
- Keep control coverage, safety evidence and the acceptance decision distinct. State the safety claim, supporting evidence and assumptions. Effect freedom or absence of exceptions alone does not establish whole-change safety: a pure, terminating change can still alter a return value or business result. Partial or missing proof must not be treated as sufficient proof, nor as proof of an actual hazard.
- An unconsumed Stream, an uninvoked lambda or callback, or a path receiving no traffic does not exempt changes to that code. Runtime inactivity or equal observed output must never justify a controlled verdict.
- Static folding of explicitly selected controls and the corresponding static proofs are in scope. Do not substitute speculative runtime reachability or observed execution for control coverage.
- Use actual packaged JAR/WAR classes and their declared dependencies as graph inputs. Published library JARs are valid static inputs without an application launcher. Do not merge unrelated modules' compiler outputs into an invented deployment graph.
- Selecting an artifact establishes the analysis input, not a startup contract. Do not require a runnable entrypoint, `Main-Class`, a container installation or a supported startup script to analyze a selected JAR/WAR. Missing static type or call evidence remains an analysis gap, not a startup-compatibility verdict.
- Do not expand the task into JVM startup-option compatibility checks or runtime-environment behavior modeling. Reading archive contents to establish the static input is distinct from verifying how an application starts or behaves at runtime.
- Runtime executions in tests may independently validate fixture behavior. They are not the engine's analysis method, cannot establish control coverage, and cannot by themselves justify accepting an uncontrolled change. Golden cases must assert static verdicts independently of whether observed outputs differ.
- General uncontrolled-but-safe acceptance and its fixture/result schema are tracked in [issue #6](https://github.com/johnsonlee/GraphFold/issues/6). The existing Boolean corpus labels do not establish support for that path. When implementing it, fixtures must separately assert control coverage, cited safety evidence and the acceptance decision, with paired cases accepting sufficient whole-change proof while retaining the uncontrolled classification and rejecting insufficient proof. Do not manufacture such evidence or relabel an existing controlled case to claim the capability is implemented.
- A new exposure predicate before an AB check may be eliminated only when static proof establishes that it is effect-free, nonthrowing, terminating, unused elsewhere, and redundant after folding the selected control. Being read-only or not observed to execute is insufficient.
- Review requests must be evaluated against this scope. Do not implement runtime-analysis features merely to satisfy a review comment; explain the scope boundary and address the relevant static-analysis concern.
- Multi-release JARs use only the JDK selected by the corresponding build, supplied by the integration. Do not enumerate JDK variants or select using the engine JVM. BASE and HEAD retain their own build JDK metadata.

## Module responsibilities

- Engine owns Git fetch/checkout/diff, static analysis and the input contract integrations supply. It knows nothing about Gradle or Maven and never invokes build tools.
- Reuse JGit operations and diff types; do not reimplement checkout, object traversal, or Git change enums and edit records.
- Keep only Graphite API integration in `engine.graphite`. ASM analysis belongs in `engine.bytecode.asm`, SootUp IR analysis in `engine.bytecode.sootup`, and comparison snapshots in `engine.snapshot`. Being called by a Graphite adapter does not make code part of that adapter.
- Gradle plugin (`plugin/gradle`) reads the live in-process Gradle model. It does not launch gradlew or inject an init script.
- Maven plugin (`plugin/maven`) reads MavenProject/MavenSession in the current lifecycle. It does not launch an external mvn.
- Plugins derive source roots, source sets, compiler outputs and classpaths from their build model to improve source attribution. Missing mapping evidence never prevents graph construction or folding.
- Each integration depends only on engine; Gradle and Maven plugins must never depend on each other. There is no shared build-tool autodetection/runner module.
- Engine owns the build-tool-independent packaged-input contract, archive decoding and snapshot storage under `engine.inputs`. Compile these sources once in engine; integrations consume its API rather than sharing source directories. Gradle/Maven model extraction stays in the respective plugins. Archive validation precedes graph analysis and must not impose deployment, startup or container compatibility requirements.

## Performance acceptance

Performance regression testing must use representative multi-graph load tests as its only performance acceptance standard. Single-graph runs are correctness gates only: do not spend time on single-graph performance measurements, performance diagnosis, candidate selection, or regression acceptance. Loading multiple graphs does not qualify when the measured request still targets only one. Preserve historical measurements without treating them as performance evidence under this rule.
