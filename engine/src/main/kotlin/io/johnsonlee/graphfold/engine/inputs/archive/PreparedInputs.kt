package io.johnsonlee.graphfold.engine.inputs.archive

import io.johnsonlee.graphfold.engine.inputs.PreparedRevision
import io.johnsonlee.graphfold.engine.inputs.StaticScope
import java.nio.file.Path

/** Preparation closes dependency/archive selection before graph analysis. */
internal fun prepareRevision(
    sha: String, source: Path, project: InputProject, output: Path, cache: DependencyInputCache,
): PreparedRevision {
    val scopes = inputScopes(project)
    val selected = scopes.mapValues { (identity, scope) ->
        val directory = output.resolve(identity.toByteArray().joinToString("") { "%02x".format(it) })
        val archive = scope.archiveInput
        val input = if (archive == null) scope else prepareArchiveInput(archive, scope, directory.resolve("archive"), scope.javaVersion)
        input to input.toStaticScope(directory.resolve("dependencies"), cache, scope.javaVersion)
    }
    val inventory = if (selected.values.all { it.first.production != null }) {
        aggregateInputInventory(selected.mapValues { it.value.first })
    } else project.production
    return PreparedRevision(sha, source, selected.mapValues { it.value.second }, inventory)
}

internal fun InputProject.toStaticScope(
    output: Path, cache: DependencyInputCache = DependencyInputCache(), variantThreshold: Int = 0,
): StaticScope {
    require(analysisScopes.isEmpty()) { "Select one prepared scope before normalization" }
    val project = this
    val dependencies = (project.dependencies + project.production?.resourceDependencies.orEmpty()).distinct()
    val effective = normalizeDependencyInputs(dependencies, output, applicationClasses = project.classes,
        cache = cache, variantThreshold = variantThreshold)
    val mapped = dependencies.zip(effective).toMap()
    val inventory = project.production?.let { it.copy(resourceDependencies = it.resourceDependencies.map(mapped::getValue)) }
    return StaticScope(project.classes, project.sourceRoots, project.dependencies.map(mapped::getValue),
        project.originDirectory, project.originRunId, project.sourceOutputs, inventory, project.compilerEvidence,
        project.sourceDependencies, project.sourceClasspaths, project.declaredSources,
        requireOriginClassHashes = project.requireOriginClassHashes,
        provenanceIssues = project.provenanceIssues)
}
