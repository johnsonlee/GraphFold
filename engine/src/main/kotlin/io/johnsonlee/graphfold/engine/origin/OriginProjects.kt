package io.johnsonlee.graphfold.engine.origin

import io.johnsonlee.graphfold.engine.build.CompiledProject
import io.johnsonlee.graphfold.engine.git.ChangedFile
import java.nio.file.Path

internal data class OriginProject(
    val compiled: CompiledProject, val sourceClasses: Path, val catalog: OriginCatalog, val owners: Map<String, String>,
)

internal data class OriginProjects(val base: OriginProject?, val head: OriginProject?, val diagnostics: List<String>)

/** Never analyze an unverified or partly normalized compilation as though provenance succeeded. */
internal fun prepareOrigins(
    base: CompiledProject, head: CompiledProject, baseRoot: Path, headRoot: Path, changes: List<ChangedFile>, output: Path,
): OriginProjects {
    val old = readOrigins(base, baseRoot)
    val new = readOrigins(head, headRoot)
    val failures = old.diagnostics.map { "base: $it" } + new.diagnostics.map { "head: $it" }
    if (failures.isNotEmpty()) return OriginProjects(null, null, failures)
    val renamed = changes.mapNotNull { change ->
        val before = change.basePath
        val after = change.headPath
        if (before != null && after != null && before != after) before to after else null
    }.toMap()
    val alignment = alignOrigins(old.origins, new.origins, renamed, old.compiledOwners + new.compiledOwners)
    return if (alignment.diagnostics.isNotEmpty()) OriginProjects(null, null, alignment.diagnostics) else {
        val normalizedBase = normalizeOriginClasses(base.classes, output.resolve("base"), alignment.baseOwners, base.dependencies)
        val normalizedHead = normalizeOriginClasses(head.classes, output.resolve("head"), alignment.headOwners, head.dependencies)
        val selectionBase = selectionClasses(base, normalizedBase, output.resolve("selection-base"))
        val selectionHead = selectionClasses(head, normalizedHead, output.resolve("selection-head"))
        OriginProjects(
            OriginProject(base.copy(classes = normalizedBase.classes), selectionBase, old, normalizedBase.owners),
            OriginProject(head.copy(classes = normalizedHead.classes), selectionHead, new, normalizedHead.owners),
            normalizedBase.diagnostics.map { "base: $it" } + normalizedHead.diagnostics.map { "head: $it" },
        )
    }
}

/** Queries see original JVM identities without the compiler instrumentation's private annotations. */
private fun selectionClasses(project: CompiledProject, normalized: OriginNormalizationResult, output: Path): Path =
    if (normalized.owners.isEmpty()) normalized.classes else normalizeOriginClasses(project.classes, output, emptyMap()).classes
