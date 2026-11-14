package io.johnsonlee.graphfold.engine

import io.johnsonlee.graphfold.engine.git.ChangedFile
import io.johnsonlee.graphfold.engine.git.JGitRepositories
import io.johnsonlee.graphfold.engine.git.diff
import io.johnsonlee.graphfold.engine.inputs.PreparedRevision
import org.eclipse.jgit.storage.file.FileRepositoryBuilder

/** Opaque comparison whose changed paths and hunks are derived by the engine from Git commits. */
class PreparedComparison private constructor(@get:JvmSynthetic internal val request: PreparedAnalysisRequest) {
    internal companion object {
        @JvmSynthetic
        fun from(request: PreparedAnalysisRequest): PreparedComparison = PreparedComparison(request)
    }
}

internal data class PreparedAnalysisRequest(
    val base: PreparedRevision, val head: PreparedRevision, val changes: List<ChangedFile>, val fold: String,
)

internal fun prepareComparison(base: PreparedRevision, head: PreparedRevision, fold: String): PreparedComparison {
    val repositories = listOf(base, head).map { revision ->
        val directory = requireNotNull(FileRepositoryBuilder().findGitDir(revision.sourceDirectory.toFile()).gitDir) {
            "Prepared source checkout is not a Git repository: ${revision.sourceDirectory}"
        }.toPath()
        JGitRepositories.open(directory, revision.sourceDirectory).use { repository ->
            require(repository.resolve("HEAD")?.name == revision.sha) {
                "Prepared source checkout does not match Git revision ${revision.sha}"
            }
        }
        directory
    }
    val changes = diff(repositories.last(), base.sha, head.sha)
    if (changes.isEmpty()) validateUnchangedGitInputs(base, head)
    return PreparedComparison.from(PreparedAnalysisRequest(base, head, changes, fold))
}
