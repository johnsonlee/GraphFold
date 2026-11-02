package io.johnsonlee.graphfold.engine.graphite

private val incompleteDiagnosticPrefixes = listOf(
    "Skipping method ",
    "Skipping methods for ",
    "Skipping control-flow extraction for ",
    "Failed to extract artifact dependencies from ",
    "Warning: Recovered ",
)

/**
 * Recognizes the diagnostic records emitted by the pinned Graphite frontend's verbose callback.
 * Inspect the whole callback at offset zero: enum dumps contain application strings, including newlines,
 * and trimming or splitting them would turn their contents into fabricated frontend diagnostics.
 * This adapter must be checked against SootUpAdapter and JavaProjectLoader when upgrading Graphite;
 * Graphite does not currently expose a structured diagnostic callback.
 */
internal fun isGraphiteIncompleteDiagnostic(message: String): Boolean =
    incompleteDiagnosticPrefixes.any(message::startsWith)
