# Dependency graph probe

The executable probe is [`DependencyGraphProbeTest`](../../../engine/src/test/kotlin/io/johnsonlee/graphfold/engine/graphite/DependencyGraphProbeTest.kt). Its [recorded output](probe-results.json) is from Graphite **2.12.0**, Kotlin compiler **2.1.20**, and the actual **kotlin-stdlib 2.1.20 JAR**. The test compiles a Kotlin application and a separate Java dependency, then loads their classes and the unmodified stdlib JAR with `includeLibraries=true`, empty `includePackages`, and `interproceduralDataflow=true`. It folds `Probe.enabled()` to false.

Run:

```shell
./gradlew :engine:test --tests '*DependencyGraphProbeTest' :engine:detektTest --console=plain
```

The test and test-source static analysis passed. This is a **Graphite graph / folded IR capability probe**, not a GraphFold verdict test. That historical probe alone does not establish an engine PASS verdict or a performance acceptance result; the separate integration evidence below verifies engine outcomes.

## Observed capabilities

| Scenario | Evidence |
| --- | --- |
| Real stdlib dependency bodies | The snapshot captures 10,079 methods across the application, dependency and stdlib; the graph contains 61,407 call sites. `StringsKt__StringsKt.trim(CharSequence)` has an actual body. |
| Application to dependency arguments and returns | Three `PARAMETER_PASS` and three `RETURN_VALUE` edges cross the custom dependency's method boundaries. The probe asserts that both kinds exist. |
| Hardcoded resource loading in a library | `Reader.hardcoded()` is captured with resource read `messages` / `unused`. The method remains in the inventory, while its call in folded `Probe.run` disappears. Inventory membership must not be confused with execution reachability. |
| Dynamic resource loading in a library | `Reader.dynamic(name)` is captured with an unknown bundle and key `unused`. The graph exposes the parameter boundary, but this test does not resolve the dynamic name into a constant. Unknown names must remain conservative. |
| Dependency invokes application callback | Graphite emits a derived call from `Reader.callback` to `Probe.callback$lambda$0`, with the interface invocation as its origin. The application lambda has a `messages` / `unused` resource read. The test asserts the derived call and the read; it does not rely solely on the lambda creation edge. |
| Frontend omissions in this fixture | Body interception recovery count is zero. No `Skipping method`, `Skipping control-flow`, `Failed to`, or `Recovered` messages were reported. Absence of these messages is not a universal completeness guarantee. |

## Probe interpretation

At the baseline commit recorded in the probe output, GraphFold’s resource extractor assigns `trim(CharSequence)` a wildcard resource read. Its body calls `Intrinsics.checkNotNullParameter`, `CharSequence.length`, `charAt`, `subSequence`, and `CharsKt.isWhitespace`. Therefore loading dependency bodies alone does **not** prove that the concrete application string-processing path is resource-free. GraphFold must consume the expanded graph, resolve applicable dispatch / value effects or exact models, and retain unknown reads where it cannot prove the target.

At the baseline commit, the analyzer's application-only snapshot filter and package restriction prevent it from using this evidence. Removing the repository-wide dependency veto must be accompanied by reachable dependency effects, initialization and callback handling, and explicit unknown boundaries. Arbitrarily trusting a library namespace or relying on absence of a resource name in a constant pool would not follow from this probe.

The experiment demonstrates that basic dependency bodies, cross-method edges and the tested callback do not require new Graphite functionality. It does not establish completeness for arbitrary frameworks, reflection, native code, dynamic loaders, call-context separation or omitted methods. The upstream problem statement should separate those requirements from the integration work already possible with Graphite 2.12.0.

This run is a correctness probe. It uses one combined application graph and supplies no performance acceptance evidence under the repository's representative multi-graph-load requirement.

## GraphFold integration

[`DependencyResourceIntegrationTest`](../../../engine/src/test/kotlin/io/johnsonlee/graphfold/engine/DependencyResourceIntegrationTest.kt) builds real Gradle Kotlin projects with stdlib and separate dependency JARs, commits base/head properties changes, and asserts the engine verdict. All eleven cases passed in both the targeted integration run and the full quality gate: safe string templates, unreachable dependency readers, folded dependency readers and runtime-only identity calls pass; a changed surviving key, hardcoded library read, dynamic name, callback read, static initialization read and runtime-only reader fail. An unknown CharSequence implementation and an application field with an unmodeled configuration-injection annotation also block the proof. The full quality-gate record is in [validation.md](../../validation.md) and the [machine-readable evidence](../validation/dependency-validation-2026-11-02.json).

The implementation keeps dependency methods out of application entries and source comparison. Only resource proof requests expand dependency bodies, using both compiled and exported runtime inputs. Effects propagate over the actual folded Graphite calls, and missing bodies or unproven dispatch stay unknown. A separate bytecode declaration inventory validates method capture and initialization metadata; it does not construct a second call graph. Field initialization resolves the JVM declaring owner. Exact Kotlin Intrinsics contracts require unique byte identity with the engine's fixed stdlib; replacement implementations use their captured bodies.

This is still a conservative analysis. Unknown `CharSequence` implementations, unresolved callback targets, native methods, custom bundle providers, JPMS service-factory declarations, implicit manifest classpaths, unsupported archive layouts and incomplete frontend capture cannot produce an absence proof. Resource parameters are not resolved across call contexts. Dependency expansion currently processes the supplied classes as a whole and has no representative multi-graph performance acceptance result. Structured method-level completeness and demand-driven expansion remain useful upstream work.

The [JPMS provider probe](provider-probe.md) independently demonstrates why a static provider factory cannot be ruled out by checking implemented interfaces alone. Module descriptors with service declarations therefore remain outside the complete resource scope.

## Inherited application APIs

Dependency methods can become application APIs through inheritance even when no application method invokes them. Resource effect analysis therefore attaches callable inherited methods to the corresponding application owner's captured methods. Public and protected methods, visible package methods, superclass static methods and interface defaults participate; unrelated dependency APIs, constructors, private methods and interface static methods do not become roots. Direct overrides and static hiding mask matching JVM signatures, and superclass implementations take precedence over interface defaults. Missing ancestry or ambiguous interface targets retain an unknown effect. This deliberately conservative attachment may report inherited API effects alongside another method of the same application owner; it does not make every dependency method an application entry point.

`InheritedResourceIntegrationTest` exercises these boundaries with separate compiled dependency and application classes. It also checks that a library resource read inherited by the application's public type is retained even without a direct application call.

Resource-only entry evidence also covers an exposed inherited static method or static field when the application type has no declared reachable method (for example, it has only a private constructor). Field access initializes the resolved declaring owner, including an interface; it does not execute that constructor. A type whose only state and constructors are private does not gain an initialization root merely because its class is public. Missing hierarchy or field inventory remains unknown. `InheritedResourceEntryTest` exercises these cases, while GraphFold's comparison restricts entry activation to baseline exposed types and independently activated owners so a new unused type is not promoted automatically.
