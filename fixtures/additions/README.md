# Physical source-file additions

Each Java/Kotlin pair adds a complete new public Additional source file. Unused cases
have no references to the added type and must PASS. Used cases update the existing
production run method to invoke Additional.apply, which changes the result and must
FAIL without diagnostics. AdditionIntegrationTest consumes full checked-in base/head
trees through real Git commits and the SDK.
