package io.johnsonlee.graphfold.engine.inputs

import java.nio.file.Path

/**
 * Adapter-prepared static inputs and optional source evidence.
 *
 * [classes] is a directory of application class files, never an unexpanded application archive.
 * [dependencies] contains normalized dependency JARs or class directories with archive variants already selected.
 * Source evidence supports attribution and proof precision; its absence must not prevent graph construction or folding.
 */
data class StaticScope(
    val classes: Path, val sourceRoots: List<Path>, val dependencies: List<Path>, val originDirectory: Path? = null,
    val originRunId: String? = null, val sourceOutputs: Map<Path, List<Path>> = emptyMap(),
    val production: ContentInventory? = null,
    val compilerEvidence: List<CompilerEvidence> = emptyList(),
    val sourceDependencies: List<Path>? = null,
    val sourceClasspaths: Map<Path, List<Path>> = emptyMap(),
    val declaredSources: List<DeclaredSourceRoot> = emptyList(),
    /** Cached or independently emitted manifests must bind every origin record to exact compiler class bytes. */
    val requireOriginClassHashes: Boolean = false,
    val provenanceIssues: List<String> = emptyList(),
)

/** Build-declared source ownership; never additional graph inputs. */
data class DeclaredSourceRoot(val project: String, val sourceSet: String, val root: Path)

/** Original compiler evidence used only for source attribution. */
data class CompilerEvidence(val classes: Path, val manifest: Path)

data class PreparedRevision(
    val sha: String, val sourceDirectory: Path, val scopes: Map<String, StaticScope>,
    val inventory: ContentInventory?,
)
