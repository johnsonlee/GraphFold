# Graphite problem statement: dependency-aware proof over folded graphs

Status (2026-11-11): the graph-side contract of #192 was delivered by [Graphite #193](https://github.com/johnsonlee/graphite/pull/193), and the issue is closed. GraphFold now targets **Graphite 2.15.0**, using its same-build folded-body observer. The release artifacts replace the earlier isolated alpha1 build; current regression, coverage and hosted CI results are recorded in [the validation record](validation.md). This document preserves the original requirements and baseline reproduction; they are not a list of wholly outstanding upstream defects or a claim that the new GraphFold integration has passed validation.

Upstream issue: [johnsonlee/graphite#192](https://github.com/johnsonlee/graphite/issues/192).

Historical baseline: Graphite **v2.12.0**; GraphFold **11333dcb7518b9658e52d487cfbd58186420b70d**.

Historical follow-up evidence: the [dependency probe](research/dependencies/README.md) ran against the actual Kotlin stdlib 2.1.20 JAR. Graphite 2.12.0 already captured dependency bodies, parameter/return edges, folded call removal, and the tested dependency-to-application callback. These basic capabilities did not require a new upstream API. Those probe results retain their original version and do not validate the observer integration.

## Current delivery and integration

Graphite provides independent `DependencyBodyScope`, structured `AnalysisCompleteness`, artifact/class provenance, explicit duplicate and dispatch evidence, and budgeted reachable expansion. Graphite 2.15.0 additionally provides `JavaProjectLoader.loadObserved`: application and dependency method observations, owned immutable folded snapshots and method receipts are reconciled with the final graph, completeness and FoldReport of the same build. A callback is provisional until that load succeeds; failed capture cannot yield a usable partial proof.

GraphFold adopts that observer instead of `SnapshotExtension` service discovery. It does not construct a separate capture graph or classify dependency methods as application methods to receive their bodies. The plain Cypher selection graph remains necessary. The API and GraphFold adapters remain coupled to SootUp; this is not a backend-neutral IR migration.

Resource proofs initially use `DependencyBodyScope.Declared(artifacts = listOf("*"))`. Eager scope retains inherited application APIs and exposed static-field initialization, whose activation may not appear as an ordinary application invocation. Production use of `Reachable` remains downstream work until those implicit roots are covered. Resource semantics, cross-call name/key specialization, obligation relevance and unsupported runtime layouts also remain GraphFold responsibilities.

Maven Central publication of `2.15.0-alpha1` was blocked by the upstream Sonatype quota. Earlier alpha1 source verification used artifacts built in isolation from exact tag commit [`907bc9ad`](https://github.com/johnsonlee/graphite/commit/907bc9adf24dbd238d72c08127827e6481cc6fe7); this does not verify published Central binaries or establish release availability. The temporary repository is local verification infrastructure.

Selection uses the parsed Cypher AST to certify direct CallSite-property queries that do not depend on unrelated native or dispatch evidence. Other query shapes conservatively require complete graph facts; extraction failures and partial derived-target selection remain blocking. Resource-call models discharge only their specific resource uncertainty, while structural gaps stay intact. A module-descriptor-only multi-release exemption requires independent archive inspection and does not excuse executable variants, providers, malformed/duplicate entries or unknown resource layouts.

The unchanged-outside-closure source proof discharges an individual `dispatchOnly` gap only when all application methods outside its actual comparison closure are unchanged in their unnormalized folded representation, global class/field/entry/initialization/override/provenance metadata agrees, and no alignment or normalization has erased differences. Methods inside the closure still require comparison. Body, recovery and other gaps remain; resource proofs and selection checks never use this exemption, and `resourceEffectModeled` alone does not authorize it. An apparently unreachable edit outside the closure can therefore still block alongside an unknown call. Raw-change and metadata checks are cached once per snapshot pair; no performance acceptance is claimed.

Two independent downstream proofs complement that contract. A complete supplied-input class-header inventory can establish that a concrete application owner has no subtypes; only surviving direct calls to methods declared by that owner qualify. Abstract/interface owners, unknown hierarchies, duplicate/runtime-variant classes and derived targets do not qualify. Dynamically generated or loaded subclasses remain outside the closed input model; body/recovery/resource effects and selection evidence are not waived. Separately, an obligation's own surviving method difference reached without unresolved dispatch, or an independently reached initialization-dependency delta, already disproves structural equivalence. This only explains FAIL, never PASS, and cannot borrow another obligation's difference. Remaining dispatch in a method prevents all its outgoing edges from serving as that independent witness. See the design document for boundaries.

New-version correctness, receipt reconciliation, coverage gates and observer-enabled representative multi-graph performance must be verified separately. Historical reports below are not evidence that these checks have run.

## Problem

GraphFold needs to determine whether a change between two JVM application builds remains observable after applying the same caller-supplied fold assumptions. Application behavior can cross dependency boundaries. A proof that stops at those boundaries cannot establish that a resource change is harmless, even when the application-side graph has no surviving reader.

The immediate example is a properties resource change whose only reader is removed by folding. At the baseline commit, GraphFold rejects that proof whenever the runtime classpath contains non-application classes. An unrelated dependency, including `kotlin-stdlib`, therefore turns a valid application-side proof into an analysis failure.

We need to follow the **folded application execution paths through dependency code**, retaining the associated dataflow and explicit evidence of any analysis gaps. Dependency presence alone must not imply resource access. Conversely, an absent graph path must not be presented as proof when the relevant body, dispatch target, initialization, or callback was not analyzed.

The original request concerned reusable graph construction and completeness capabilities, now delivered upstream as summarized above. Resource semantics, Git diffs, and the final GraphFold verdict remain downstream responsibilities.

## Observed behavior and reproduction

The [GraphFold review thread](https://github.com/johnsonlee/GraphFold/pull/1#discussion_r4434859776) reports the following behavior on the baseline commit:

| Runtime | Surviving application path | Baseline outcome |
| --- | --- | --- |
| Java, no external dependencies | Standard scalar output followed by an unchanged bundle-key read | PASS |
| Java, no external dependencies | An unchanged bundle-key read followed by string concatenation | PASS |
| Kotlin with `kotlin-stdlib` | The equivalent unchanged bundle-key read and string template | FAIL: `PRODUCTION_OUTPUT_CHANGED` |

The Java controls are covered by the existing parameterized [BuildInputIntegrationTest](../engine/src/test/kotlin/io/johnsonlee/graphfold/engine/BuildInputIntegrationTest.kt). The Kotlin full-build row records the reviewer-reported baseline. The subsequent [real Kotlin integration regression](../engine/src/test/kotlin/io/johnsonlee/graphfold/engine/DependencyResourceIntegrationTest.kt) independently verifies PASS after the GraphFold integration fix and FAIL for a surviving live-key edit. Java/Kotlin resource IR tests and prior verification are recorded in [validation.md](validation.md).

A minimal reproduction shape is:

```kotlin
class Example {
    fun value(): String {
        val bundle = java.util.ResourceBundle.getBundle("messages")
        if (Ab.enabled("feature")) return bundle.getString("new")
        return "${bundle.getString("old")}!"
    }
}
```

Use the same source in both revisions, with a normal Kotlin JVM Gradle build and `kotlin-stdlib` on its runtime classpath. `Ab.enabled` is a fixed public API selected by the fold rule.

```properties
# base: src/main/resources/messages.properties
old=Hello
```

```properties
# head: src/main/resources/messages.properties
old=Hello
new=Bonjour
```

Analyze base and head with `Ab.enabled("feature")` folded to `false`. The `new` read must disappear in both folded programs. Under a complete, declared execution scope with no other readers, adding `new` should pass. Changing the surviving `old` value must still fail.

For a Graphite-owned regression, the same semantics can be tested with separate application and dependency JARs, without invoking Git or GraphFold. Use a dependency helper that delegates a bundle name or key through parameters and returns; the graph should expose the surviving path to the resource API after folding.

## Existing capability versus baseline integration defects

The following findings describe the historical v2.12.0 source and the original integration defects. They do not describe the current observer/completeness API:

- [`LoaderConfig`](https://github.com/johnsonlee/graphite/blob/v2.12.0/frontend/jvm/core/src/main/kotlin/io/johnsonlee/graphite/input/ProjectLoader.kt) supports library loading, package filtering, folding, and optional interprocedural dataflow.
- [`InterproceduralDataflow`](https://github.com/johnsonlee/graphite/blob/v2.12.0/frontend/jvm/sootup/src/main/kotlin/io/johnsonlee/graphite/sootup/InterproceduralDataflow.kt) connects arguments to callee parameters and callee returns to call results, including known virtual overrides. A callee without a processed body contributes no corresponding body edges.
- [`SootUpAdapter`](https://github.com/johnsonlee/graphite/blob/v2.12.0/frontend/jvm/sootup/src/main/kotlin/io/johnsonlee/graphite/sootup/SootUpAdapter.kt) processes methods, fields, and extensions for classes admitted by its class filter. Loading a library for resolution does not imply that all its method bodies are represented.
- Graphite already records class origins and artifact dependencies. The scope request below is to preserve and expose that provenance while separating application identity from dependency expansion, not to introduce provenance from scratch.
- `buildCallGraph` can run a separate SootUp call-graph algorithm, but its result is not merged into the Graphite graph. Switching that option on is not sufficient to provide the required traversal.

At the baseline commit, GraphFold has three integration defects to correct independently of upstream work:

1. `includePackages` is populated with application owners, restricting dependency method-body analysis even though `includeLibraries=true`.
2. Interprocedural dataflow is enabled for fold selection, but is not enabled in the final folded-graph configuration.
3. The captured method map is filtered back to the application inventory, discarding dependency methods from the comparison snapshot.

See [GraphiteAnalyzer.kt](../engine/src/main/kotlin/io/johnsonlee/graphfold/engine/graphite/GraphiteAnalyzer.kt). At the same baseline, [ProductionInventory.kt](../engine/src/main/kotlin/io/johnsonlee/graphfold/engine/build/ProductionInventory.kt) sets `resourceConsumersKnown=false` on discovery of non-application dependency classes. That repository-wide Boolean is a downstream conservative fallback, not a Graphite requirement.

Removing filters and retaining dependency bodies is a useful correctness prototype. It does not by itself establish scalable expansion, complete entry/dispatch coverage, or precise cross-call dataflow. These are the upstream concerns below.

## Original graph contract and retained acceptance requirements

The requirements below preserve the issue’s scope. The implementation status is summarized above; consumer-side semantics and integration validation are not implied by upstream delivery.

### 1. Separate application identity from dependency body scope

A consumer must be able to declare its application artifacts and analysis roots while allowing dependency method bodies to participate in the proof.

- Including a dependency body must not promote all public methods of that dependency to application entry points.
- Application/dependency identity and artifact provenance must remain available independently of inclusion filters.
- Method identity must include its JVM descriptor. Evidence must also identify the resolved artifact/class definition, preserving classpath precedence and ambiguity information.
- Duplicate definitions, unsupported archive layouts, or unsupported multi-release selection must be surfaced rather than silently treated as a unique implementation.
- Existing package filters should remain usable for their current purpose; callers need an explicit way to distinguish deliberate exclusion from absence of a method body.

The request is for this contract, not a particular set of configuration property names.

### 2. Expose scoped, structured completeness evidence

Consumers need machine-readable evidence for the analyzed scope, associated with a method, call site, artifact, or unresolved boundary as appropriate. Human-readable log parsing and aggregate fallback counts are insufficient for proof decisions.

In v2.12.0, `SootUpAdapter.processMethod` creates method/return nodes before completing body processing and can catch an out-of-memory failure and continue. Node existence therefore does not establish that the body was analyzed completely. Partially emitted evidence must be rolled back or explicitly marked incomplete; it must never be mistaken for a complete body with no effects.

Evidence should distinguish at least:

- successfully processed bodies, including bodies with no relevant outgoing effects;
- abstract/native/external methods requiring a model or further resolution;
- bodies excluded by configuration;
- body-loading, frontend, transformation, or graph-extraction failures;
- unresolved dispatch/callback targets and unsupported dynamic behavior;
- incomplete expansion due to cancellation or resource budgets.

Use finite typed codes/statuses with explanatory messages and provenance. Preserve this evidence across graph save/load, together with the scope and assumptions to which it applies. Older graphs without completeness evidence must remain distinguishable from graphs verified complete for a scope. The exact schema can be designed upstream.

Completeness is relative to declared roots, runtime assumptions, resolution scope, and supported semantics. It is not a claim to cover arbitrary JVM execution. GraphFold must be able to determine which proof depends on a gap; a gap in an unrelated, inactive part of a dependency should not automatically poison every proof.

### 3. Support dependency closure expansion over folded behavior

Starting from consumer-supplied roots, graph construction or an expansion API should make the necessary dependency bodies available and link them into the same analysis:

```text
application entry
  → surviving application/dependency calls
  → required initialization and supported callback targets
  → observable API boundary
```

- Expansion must respect the selected fold scenario and expose evidence corresponding to the transformed bodies. Pre-fold discovery may be used to obtain inputs, but eliminated calls must not remain active proof dependencies.
- Cycles and recursion require a terminating conservative computation.
- Required class initialization, virtual dispatch, and supported lambda/callback flows must be included or explicitly reported as unresolved.
- Framework-discovered entries must be supplied by a consumer/model or reported as an unsupported boundary. Absence of an ordinary invocation edge is not evidence that such an entry is inactive.
- A missing body, skipped target, or exhausted budget must not be indistinguishable from a method with no outgoing calls.
- Application provenance and dependency provenance must remain separate throughout expansion.

An initial implementation may process a declared dependency scope eagerly. For large repositories, selective expansion and reuse are needed; building every dependency body should not be the only supported route.

### 4. Preserve the dataflow needed by consumers

GraphFold needs to follow resource names, keys, and returned resource handles across application/library boundaries. Existing parameter/return links are the starting point.

For example:

```java
// A dependency API; the application may never call ResourceBundle directly.
static String read(String bundleName, String key) {
    return ResourceBundle.getBundle(bundleName).getString(key);
}
```

The graph must retain the connection between a surviving invocation's arguments and the loader/read in this body, including values returned through helpers. Field/heap-mediated or callback-mediated flows must be represented to the supported precision or expose a gap.

A context-insensitive union is acceptable as an explicitly conservative first stage. It may overapproximate reads and cause false positives. Higher precision should preserve call-site context or parameterized summaries so arguments from different calls are not silently combined into a claimed exact bundle/key pair.

Graphite need not implement properties parsing or a resource-specific solver. It must expose enough nodes, edges, and uncertainty for GraphFold to perform that analysis. An inability to resolve a dynamically computed name remains a downstream unknown effect, not proof of no access.

## GraphFold responsibilities

The integration expands a declared eager dependency scope for resource proofs and consumes same-build observer snapshots together with structured graph completeness. Interprocedural dataflow is enabled for selection and resource-proof graphs; ordinary source comparison does not require it in the final graph. Dependency resource effects are consumed before retaining only application methods for source comparison. The earlier integration already replaced the blanket dependency veto with runtime-layout prerequisites and graph evidence and added inherited APIs, exposed static fields, initialization and callback regressions. Those historical results require rerunning on the new observer path. Unsupported scope or unresolved effects remain blocking, and dependency-content identities still participate in production-output comparison.

Downstream responsibilities remain:

- provide application roots and fold scenarios for both revisions;
- define resource loader/read semantics, lookup order, locale fallback, aliases and supported packaging;
- resolve names/keys only to the precision justified by graph evidence; cross-call context-sensitive resource values are not yet implemented;
- keep unknown effects blocking when they affect the changed resources;
- report graph evidence without attributing source findings to unchanged dependency code;
- compare changed dependency artifacts independently of resource absence proofs.

If effect summaries are used for efficiency, they should derive from graph analysis and explicit models. Cache reuse must account for artifact contents, resolved dependency environment, runtime target, analysis/model version, and any fold assumptions used to produce the result. A package name or library version label alone is not a proof or a safe cache identity.

## Acceptance cases

Every case must assert graph evidence and completeness status, not merely a downstream PASS/FAIL. GraphFold integration tests additionally assert verdict/reason and diff-owned locations where a finding is produced.

| Case | Required evidence / downstream expectation |
| --- | --- |
| Unrelated dependency, no activated entries or relevant effects in the declared scope | Its presence alone does not invalidate a resource proof. |
| Kotlin + stdlib, folded-only new properties key, ordinary string template | Complete modeled/analyzed path; PASS for the new key. |
| Changed value of a surviving key | Surviving read remains visible; GraphFold blocks the change. |
| Dependency helper receiving a constant bundle name/key | Parameter and return flow reaches the library's loader/read. |
| Dependency contains a reachable hard-coded resource name | Reader is discovered even though no application argument contains the name. |
| Dependency computes a name from parameters/configuration | Resolve where supported; otherwise report unknown effect, never infer safety from missing literals. |
| Dependency reader exists only behind a folded-away call | It is not an active read after folding, unless another activation path remains. |
| Required static initializer reads a resource | Initialization effect is included or explicitly unresolved. |
| Application lambda/callback invoked through a dependency | Include supported callback targets; unresolved targets remain gaps. |
| Two calls to the same helper with different names/keys | Preserve context or report conservative unions; do not claim incorrect exact pairings. |
| Missing/native/filtered/malformed body, or unresolved reflective access | Structured, attributable gap. |
| Dependency cycle or expansion budget exhaustion | Termination; incomplete results cannot authorize a proof. |
| Graph persistence roundtrip, including partially emitted bodies | Preserve completeness state and call-site/artifact evidence; an incomplete body cannot become complete after reload. |
| Multiple roots/graphs and different fold scenarios | No cross-graph or cross-scenario evidence/cache contamination. |
| Changed dependency/classpath precedence with cached analysis | Invalidate or relink affected evidence; stale summaries cannot authorize PASS. |

Existing Graphite graph/fold behavior and existing GraphFold controlled/uncontrolled fixtures must remain covered. The first delivery need not solve arbitrary reflection, JNI, custom class loaders, heap flows, framework discovery, or SPI; unsupported cases must remain explicit. SPI expansion is not a prerequisite for the first delivery.

## Delivery and performance

The original sequence is now split into delivered contracts and downstream validation:

1. The baseline application→dependency→resource prototype and GraphFold regressions remain historical correctness evidence. Rerun the cases against the 2.15.0 observer integration, including same-build receipt and completeness checks.
2. Scope/provenance, structured completeness, reachable expansion and same-build method observation are available upstream; they are no longer blanket upstream blockers.
3. GraphFold retains declared eager resource scope initially. Validate all implicit roots before switching to selective expansion, define reusable analysis identities before adding caches, and improve call-context precision where measured false positives justify it.

Do not claim performance acceptance from a single graph, or from loading multiple graphs while the measured request still targets only one. Performance acceptance requires representative multi-graph load tests whose measured requests exercise multiple graphs, including dependency-heavy inputs and supported expansion/reuse paths. Record workload, hardware, memory, latency and repeated-run results against an agreed baseline; numeric budgets should be set before accepting the optimization. Single-graph runs remain correctness checks only.

## Non-goals and unsafe shortcuts

- No reimplementation of a separate call graph in GraphFold when Graphite can supply the required graph evidence.
- No library/package allowlist that grants safety merely because a dependency is Kotlin stdlib, Jackson, Guava, or another familiar library.
- No proof from the absence of a resource name in a constant pool. Names can be assembled, configured, passed across calls, or discovered implicitly.
- No automatic equivalence between “no outgoing graph edge” and “no runtime effect.”
- No requirement for Graphite to understand Git diffs, AB IDs, Jira, properties-file changes, or GraphFold verdict policy.
