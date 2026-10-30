# Kotlin emitted-origin compatibility probe

Run `python3 docs/experiments/kotlin-origin/verify.py` from any directory with JDK 17+ and the listed artifacts already in the standard Gradle dependency cache. The script does not invoke Gradle or download dependencies. Missing coordinates fail immediately. It compiles the current engine provider once with compiler 2.1.20, packages its two compiler services, and uses that exact JAR for every invocation. Temporary classes, sidecars and the plugin are retained at the printed `ARTIFACTS` directory.

| Compiler | Language / actual frontend |
| --- | --- |
| 1.9.20 | 1.9 / K1 |
| 2.0.21 | 2.0 / K2 |
| 2.1.20 | 2.1 / K2 |
| 2.2.10 | 2.2 / K2 |
| 2.1.20 | 1.9 / K1 |
| 1.9.0 | Explicit unsupported-version rejection |

Required cache artifacts: `org.jetbrains.kotlin:kotlin-compiler-embeddable` and `kotlin-stdlib` for every compiler above; `org.jetbrains.kotlin:kotlin-reflect:1.6.10`; `org.jetbrains.intellij.deps:trove4j:1.0.20200330`; `org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.8.0`; `org.jetbrains:annotations:13.0`.

The fixture exercises object expressions sharing one source line, nested expressions, instance and companion initialization, inline definitions, a backend-generated crossinline copy, and a suspend lambda with a real Continuation object. Each supported compiler/frontend is tested with LF, CRLF, CR, and UTF-8 BOM plus CRLF: 20 successful compilations in total. The source includes an astral Unicode character and a tab. Assertions check completed manifests, no unresolved records, actual frontend, raw-byte source SHA-256, every allocation/declaration UTF-16 range, actual emitted class files, and copied definition provenance. Expected physical ranges are derived independently from the LF fixture prefix; the probe reads raw bytes because Python read_text() would silently translate CRLF and conceal offset errors. Output includes a SHA-256 of the single provider binary. This is compiler-hook compatibility evidence; it does not prove whole-engine equivalence, Maven classloader compatibility, or unambiguous inline creation callsites. Exact inline definition offsets are available; emitted enclosing context may be absent.

Source provenance support and analysis normalization support are separate. The current typed Kotlin metadata normalizer supports schemas through 2.1 and rejects unsupported contracts, type aliases and type annotations. It does not replace arbitrary metadata or user strings. Runtime binary names can be observable through object rendering or serialization; normalization conservatively reports these cases as analysis gaps. The marker is stripped only from analysis copies after catalog validation, never from the compilation artifacts used by downstream Kotlin compilation.

The retained `verified-2026-10-30.log` records a successful current-source run, including the explicit 1.9.0 rejection. The plugin SHA-256 in that run was `e8bc4749cbe3960847dfd43e42c6299c0825a0dfc353c120767e8df560303929`. ZIP timestamps can change this value across rebuilds; the guarantee checked here is one unchanged binary across all invocations within a run.

Serializable ancestry is resolved from compiled classes, platform class resources and the actual dependency directories/JARs without initializing classes. Missing ancestry and multi-release dependency entries whose compilation selection is unavailable produce an analysis gap. Runtime-name checks include explicit Throwable/Thread/StackWalker observation, method-reference handles, Java formatting/array helpers and Kotlin joining/string-conversion helpers; they conservatively scan the compilation rather than proving that a particular receiver is the renamed anonymous object.

Lowered lambda/continuation classes also satisfy Kotlin IR `isAnonymousObject`. The provider distinguishes compiler origins `LAMBDA_IMPL`, `SUSPEND_LAMBDA`, and `CONTINUATION_CLASS` using the actual `IrDeclarationOrigin.name` interface, whose getter ABI is shared by the tested versions. It does not infer generated classes from binary names. The latest retained matrix emits 15 records and no unresolved records for each supported invocation.

Kotlin normalizes CRLF and removes a leading UTF-8 BOM before assigning IR offsets. The provider maps compiler boundaries back to raw UTF-16 boundaries before emitting markers and sidecars. Inline marker decoding validates the already-physical offsets without converting them again. Only the leading BOM is omitted from compiler coordinates; embedded U+FEFF characters retain their width. Headless PSI uses a separate length-preserving input preparation, with token text and reported positions still read from the physical source.
