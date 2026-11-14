package io.johnsonlee.graphfold.engine.origin

import io.johnsonlee.graphfold.engine.snapshot.MethodAnalysisGap

import io.johnsonlee.graphfold.engine.inputs.StaticScope
import io.johnsonlee.graphfold.engine.git.ChangedFile
import java.nio.file.Path

internal data class OriginProject(
    val compiled: StaticScope, val sourceClasses: Path, val catalog: OriginCatalog, val owners: Map<String, String>,
    val methodDiagnostics: Map<String, List<io.johnsonlee.graphfold.engine.snapshot.MethodAnalysisGap>> = emptyMap(),
)

internal data class OriginProjects(val base: OriginProject, val head: OriginProject, val diagnostics: List<String>)

/** Failed provenance stays blocking; raw identities still permit reporting independent diff observations. */
internal fun prepareOrigins(
    base: StaticScope, head: StaticScope, baseRoot: Path, headRoot: Path, changes: List<ChangedFile>, output: Path,
): OriginProjects {
    val old = readOrigins(base, baseRoot)
    val new = readOrigins(head, headRoot)
    val failures = old.diagnostics.map { "base: $it" } + new.diagnostics.map { "head: $it" }
    if (failures.isNotEmpty()) return unverifiedProjects(base, head, old, new, output, failures)
    val renamed = changes.mapNotNull { change ->
        val before = change.basePath
        val after = change.headPath
        if (before != null && after != null && before != after) before to after else null
    }.toMap()
    val alignment = alignOrigins(old.origins, new.origins, renamed, old.compiledOwners + new.compiledOwners)
    return if (alignment.diagnostics.isNotEmpty()) unverifiedProjects(base, head, old, new, output, alignment.diagnostics) else {
        val normalizedBase = normalizeOriginClasses(base.classes, output.resolve("base"), alignment.baseOwners, base.dependencies)
        val normalizedHead = normalizeOriginClasses(head.classes, output.resolve("head"), alignment.headOwners, head.dependencies)
        val diagnostics = normalizedBase.diagnostics.map { "base: $it" } + normalizedHead.diagnostics.map { "head: $it" }
        if (diagnostics.isNotEmpty()) unverifiedProjects(base, head, old, new, output, diagnostics) else {
            val selectionBase = selectionClasses(base, normalizedBase, output.resolve("selection-base"))
            val selectionHead = selectionClasses(head, normalizedHead, output.resolve("selection-head"))
            OriginProjects(
                OriginProject(base.copy(classes = normalizedBase.classes), selectionBase, old, normalizedBase.owners,
                    normalizedBase.methodDiagnostics),
                OriginProject(head.copy(classes = normalizedHead.classes), selectionHead, new, normalizedHead.owners,
                    normalizedHead.methodDiagnostics),
                normalizedBase.diagnostics.map { "base: $it" } + normalizedHead.diagnostics.map { "head: $it" },
            )
        }
    }
}

/** Queries see original JVM identities without the compiler instrumentation's private annotations. */
private fun selectionClasses(project: StaticScope, normalized: OriginNormalizationResult, output: Path): Path =
    if (normalized.owners.isEmpty()) normalized.classes else normalizeOriginClasses(project.classes, output, emptyMap()).classes

/** No owner pairing or partial catalog bindings survive a failed provenance proof. */
private fun unverifiedProjects(
    base: StaticScope, head: StaticScope, old: OriginCatalog, new: OriginCatalog,
    output: Path, diagnostics: List<String>,
): OriginProjects {
    fun original(project: StaticScope, catalog: OriginCatalog, side: String): OriginProject {
        val classes = stripOriginInstrumentation(project.classes, output.resolve("unverified-$side"))
        return OriginProject(project.copy(classes = classes), classes, catalog.copy(origins = emptyList()), emptyMap())
    }
    return OriginProjects(original(base, old, "base"), original(head, new, "head"), diagnostics)
}

/** Compiler outputs are source-index evidence only; Graphite selection always uses packaged sourceClasses. */
internal fun OriginProject.sourceIndexClasses(source: Path): List<Path> {
    if (compiled.compilerEvidence.isEmpty()) return listOf(sourceClasses)
    val outputs = compiled.sourceOutputs.filterKeys { source.toAbsolutePath().normalize().startsWith(it.toAbsolutePath().normalize()) }
        .values.flatten().map { it.toAbsolutePath().normalize() }.toSet()
    return compiled.compilerEvidence.map { it.classes }.distinct().filter { it.toAbsolutePath().normalize() in outputs }
}
