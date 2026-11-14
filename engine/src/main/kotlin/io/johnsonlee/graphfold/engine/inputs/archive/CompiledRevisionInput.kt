package io.johnsonlee.graphfold.engine.inputs.archive

import io.johnsonlee.graphfold.engine.inputs.PreparedRevision
import java.nio.file.Path

internal fun inputScopes(project: InputProject): Map<String, InputProject> =
    project.analysisScopes.ifEmpty { mapOf("source" to project) }

/** Each revision retains the JDK selected by its own build. */
internal data class CompiledRevisionInput(val sha: String, val source: Path, val project: InputProject)

internal fun prepareRevisions(
    base: CompiledRevisionInput, head: CompiledRevisionInput, output: Path, cache: DependencyInputCache,
): Pair<PreparedRevision, PreparedRevision> =
    prepareRevision(base.sha, base.source, base.project, output.resolve("base"), cache) to
        prepareRevision(head.sha, head.source, head.project, output.resolve("head"), cache)
