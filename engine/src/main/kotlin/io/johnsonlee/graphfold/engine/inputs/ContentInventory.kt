package io.johnsonlee.graphfold.engine.inputs

import java.nio.file.Path

data class ContentInventory(
    val entries: Map<String, String>,
    val nonClassEntries: Map<String, String>,
    val classPlacements: Map<String, Set<String>> = emptyMap(),
    val resourceEntries: Map<String, Map<String, String>> = emptyMap(),
    val resourceIndependentEntries: Map<String, String> = nonClassEntries,
    val resourceConsumersKnown: Boolean = false,
    val resourceDependencies: List<Path> = emptyList(),
)

/**
 * Equality of complete recorded content entries only; absent evidence never establishes equality.
 * Resource proofs and class-placement checks remain separate from this comparison.
 */
fun ContentInventory?.hasSameEntries(other: ContentInventory?): Boolean =
    this != null && other != null && entries == other.entries
