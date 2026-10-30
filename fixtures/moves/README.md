# Source move fixtures

Each case stores complete base/head source trees. MoveIntegrationTest commits those
trees to an actual Git repository and calls the SDK. The manifest records expected
verdict, syntax change kind, paths, and an optional fail-closed diagnostic.

Java/Kotlin same-package directory moves and a Kotlin filename rename with stable
JvmName are negative cases. Moved Java and Kotlin methods with changed arithmetic are positive.
A content-identical Kotlin top-level filename rename changes JVM facade identity and
must fail closed with UNLOCATED_MOVE_DIFFERENCE: it has no changed source characters,
so it must not invent finding coordinates or count as a detected positive in corpus
accuracy. Reflection-bound method renames need separate analysis-gap fixtures.
