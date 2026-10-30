package io.johnsonlee.graphfold.engine.graphite

/** Structural evidence, not a proof that differing representations differ in behavior. */
data class ProgramSnapshot(
    val methods: Map<String, MethodSnapshot>,
    val declarations: Map<String, String>,
    val diagnostics: List<String>,
    val matchedRules: List<Int>,
    val fields: Map<String, FieldSnapshot> = emptyMap(),
    val classShapes: Map<String, String> = emptyMap(),
    val ignoredFields: Set<String> = emptySet(),
    val entryClasses: Set<String>? = null,
    val externalEntries: Set<String> = emptySet(),
    val initializationDependencies: Map<String, Set<String>> = emptyMap(),
    val overrides: Set<String> = emptySet(),
    val enclosingClasses: Map<String, String> = emptyMap(),
)

data class MethodSnapshot(
    val identity: String,
    val canonical: String,
    val calls: Set<String>,
    val modifiers: Set<String>,
    val lines: Set<Int>,
    val referenceCanonical: String = canonical,
    val references: List<String> = emptyList(),
)

/** Member identity is separate from metadata, so renaming cannot rewrite string constants or annotation values. */
data class FieldSnapshot(
    val identity: String,
    val declaration: String,
    val constant: String,
    val renameSafe: Boolean,
)
