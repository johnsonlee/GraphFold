# Java generated-code source locations

`JavaGeneratedSourceLocationTest` runs eight checked-in base/head pairs through the full engine using JGit-created repositories. Every pair changes exactly one character. Ungated cases must report the precise original and updated `Example.java` path, one-based line and column, with an exclusive end column. Controlled counterparts must pass without findings; neither category permits blocking diagnostics.

| Behavior | Uncontrolled changed position | Controlled changed position |
| --- | --- | --- |
| Lambda body | 4:74–75 | 5:78–79 |
| Captured lambda body | 5:83–84 | 6:87–88 |
| Anonymous callback body | 5:63–64 | 6:67–68 |
| Generic implementation reached through a compiler bridge | 5:22–23 | 5:49–50 |

The tests independently compile both revisions and inspect ASM bytecode. Lambdas must have an actual synthetic implementation method plus a LambdaMetafactory call site; captured lambdas must pass one captured argument. The generic implementation must emit an `ACC_BRIDGE | ACC_SYNTHETIC` method returning Object. Anonymous callbacks must have the expected enclosing class/method and implementation method; an anonymous class is not assumed to carry `ACC_SYNTHETIC`.

These are Java/JVM source-mapping regressions, not a claim that every compiler-generated shape or frontend is covered. The production frontend runs these tests first; an isolated UAST candidate can run the identical fixtures and assertions.
