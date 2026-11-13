package io.johnsonlee.graphfold.engine

import io.johnsonlee.graphfold.engine.inputs.StaticScope
import io.johnsonlee.graphfold.engine.git.ChangedFile
import java.nio.file.Path

/** Mixed production/build-input moves require whole-program proof; suffix alone never grants source ownership. */
internal fun isProductionSourceChange(
    change: ChangedFile, base: Collection<StaticScope>, head: Collection<StaticScope>, baseRoot: Path, headRoot: Path,
): Boolean {
    val endpoints = listOfNotNull(change.basePath?.let { Triple(base, baseRoot, it) },
        change.headPath?.let { Triple(head, headRoot, it) })
    return change.isJvmSourceChange() && endpoints.isNotEmpty() && endpoints.all { (project, root, path) ->
        ownsProductionSource(project, root, path)
    }
}

private fun ownsProductionSource(scopes: Collection<StaticScope>, checkout: Path, path: String): Boolean =
    scopes.any { scopeContainsSource(it, checkout, path) }

/** Changed static scope outputs require proof even when the triggering source belongs to another application. */
internal fun scopeOutputsChanged(base: StaticScope, head: StaticScope): Boolean {
    val before = base.production
    val after = head.production
    return before != null && after != null && before.entries != after.entries
}

internal fun changesRequiringScopeProof(
    changes: List<ChangedFile>, inputs: List<ChangedFile>, outputsChanged: Boolean, owns: (ChangedFile) -> Boolean,
): List<ChangedFile> = if (outputsChanged) changes else changes.filter { it in inputs || owns(it) }

/** canonicalFile resolves checkout aliases while also allowing an added or deleted endpoint to be absent. */
internal fun scopeContainsSource(project: StaticScope, root: Path, path: String): Boolean {
    val source = root.resolve(path).toFile().canonicalFile.toPath()
    return project.sourceRoots.any { source.startsWith(it.toFile().canonicalFile.toPath()) }
}
