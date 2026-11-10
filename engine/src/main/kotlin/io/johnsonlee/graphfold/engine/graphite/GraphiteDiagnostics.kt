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
 * Production method/dispatch failures now use structured completeness; this broader text classifier
 * remains only for diagnostic regression probes.
 */
internal fun isGraphiteIncompleteDiagnostic(message: String): Boolean =
    incompleteDiagnosticPrefixes.any(message::startsWith)

/** Artifact metadata extraction failures are not represented by method completeness. */
internal fun isGraphiteArtifactDiagnostic(message: String): Boolean =
    message.startsWith("Failed to extract artifact dependencies from ")

/** Shares the artifact-only callback filter between selection and folded loads. */
internal fun artifactDiagnosticCollector(diagnostics: MutableCollection<String>): (String) -> Unit = { message ->
    if (isGraphiteArtifactDiagnostic(message)) diagnostics.add(message)
}
