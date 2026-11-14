package io.johnsonlee.graphfold.engine.git

import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.diff.Edit
import org.eclipse.jgit.diff.EditList

/** Builds native Git metadata for tests that exercise attribution without creating a repository. */
internal fun changedFile(
    basePath: String?, headPath: String?, edits: List<Edit> = emptyList(),
    changeType: DiffEntry.ChangeType = DiffEntry.ChangeType.MODIFY, similarityScore: Int? = null,
): ChangedFile = ChangedFile(object : DiffEntry() {
    init {
        oldPath = basePath ?: DEV_NULL
        newPath = headPath ?: DEV_NULL
        this.changeType = changeType
        score = similarityScore ?: 0
    }
}, EditList().apply { addAll(edits) })
