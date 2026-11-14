package io.johnsonlee.graphfold.engine.inputs.archive

import java.nio.file.Path

/**
 * Production bytecode and provenance exported by a build adapter for one revision.
 *
 * @property classes Selected packaged application classes, or source-only compiler output.
 * A revision container with [analysisScopes] has no analysis classes of its own.
 * @property sourceRoots Declared production source directories used for source indexing.
 * @property dependencies Resolved dependency JARs or class directories needed to analyze the application.
 * @property originDirectory Compiler provenance directory, or null when no provenance was exported.
 * @property originRunId Expected provenance run identity, or null when no identity was exported.
 * @property sourceOutputs Source-root to original class-output mapping used to disambiguate module ownership.
 * @property production Actual production classpath and package inventory, or null when the adapter cannot establish its scope.
 * @property analysisScopes Independently selected artifact scopes; compiler modules are never merged into a synthetic graph.
 * Empty for a leaf scope, source-only analysis, or a revision whose selected scopes could not be prepared.
 * @property compilerEvidence Original compiler outputs and manifests used only to verify source provenance.
 * @property sourceDependencies Legacy source-binding dependencies; never additional graph inputs.
 * @property sourceClasspaths Compiler classpaths by source root, preserving each producer's symbol-resolution context.
 * @property declaredSources All build-declared source-set roots for ownership auditing, including non-production sets.
 */
data class InputProject(
    val classes: Path, val sourceRoots: List<Path>, val dependencies: List<Path>, val originDirectory: Path? = null,
    val originRunId: String? = null, val sourceOutputs: Map<Path, List<Path>> = emptyMap(),
    val production: ProductionInventory? = null,
    val analysisScopes: Map<String, InputProject> = emptyMap(),
    val compilerEvidence: List<CompilerEvidence> = emptyList(),
    val sourceDependencies: List<Path>? = null,
    val sourceClasspaths: Map<Path, List<Path>> = emptyMap(),
    val declaredSources: List<DeclaredSourceRoot> = emptyList(),
    val provenanceIssues: List<String> = emptyList(),
    val archiveInput: ArchiveInput? = null,
    val requireOriginClassHashes: Boolean = false,
    val javaVersion: Int = 8,
)

/** Build-declared source-set ownership for auditing; it does not authorize graph inputs. */
typealias DeclaredSourceRoot = io.johnsonlee.graphfold.engine.inputs.DeclaredSourceRoot

/** One complete compiler manifest and its original output; never an application bytecode input. */
typealias CompilerEvidence = io.johnsonlee.graphfold.engine.inputs.CompilerEvidence

/**
 * Input decoding could not prepare readable bytes, so graph analysis is not invoked for this revision.
 *
 * @param message Description of the unreadable or inconsistent input.
 */
class InputFailure(override val message: String) : IllegalStateException(message)


internal fun checkInput(condition: Boolean, message: String) {
    if (!condition) throw InputFailure(message)
}

internal fun regularFiles(root: Path): List<Path> = java.nio.file.Files.walk(root).use { paths ->
    paths.filter { java.nio.file.Files.isRegularFile(it) }.sorted().toList()
}

internal fun digest(path: Path): String = java.security.MessageDigest.getInstance("SHA-256")
    .digest(java.nio.file.Files.readAllBytes(path)).joinToString("") { "%02x".format(it) }
