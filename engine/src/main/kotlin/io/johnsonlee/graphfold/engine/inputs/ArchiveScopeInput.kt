package io.johnsonlee.graphfold.engine.inputs

import java.nio.file.Path

/** Packaged bytes and optional source associations exported by an integration's own model. */
data class ArchiveScopeInput(
    val id: String,
    val archive: Path,
    val dependencies: List<Path> = emptyList(),
    val sourceRoots: List<Path> = emptyList(),
    val sourceOutputs: Map<Path, List<Path>> = emptyMap(),
    val declaredSources: List<DeclaredSourceRoot> = emptyList(),
    val compilerEvidence: List<CompilerEvidence> = emptyList(),
    val originDirectory: Path? = null,
    val originRunId: String? = null,
    val sourceClasspaths: Map<Path, List<Path>> = emptyMap(),
    val projectArtifacts: Map<Path, List<Path>> = emptyMap(),
    /** Actual compiler/toolchain JDK used by the exporting build, supplied by the integration. */
    val javaVersion: Int,
) {
    init { require(javaVersion > 0) { "Build JDK version must be positive" } }
}
