# Physical source-file additions

`AdditionIntegrationTest` consumes the complete BASE/HEAD trees through real Git
commits and source compilation. Every case adds a physical production source file.

The unused and uncontrolled-reference pairs add public `Additional` types in Java
and Kotlin. Both pairs must FAIL without diagnostics: an unused production addition
still lacks selected-control coverage, and public entry points remain independent
static paths. Runtime inactivity or equal observed output is not evidence of control.

The controlled pair calls the new implementation only inside the branch removed by
folding `Ab.enabled("feature")` to false. Both must PASS with `UNREACHABLE` reasons
for the added path, proving selected-control removal against the same input before
folding. The Java helper is package-private. The Kotlin helper is a private top-level
class, emitted as a package-private JVM class; an existing Java caller in the same
package references it through ordinary Java/Kotlin interoperability. This deliberately
does not claim that a Kotlin caller in another file can access a file-private class,
or exempt a new public entry point from control coverage.
