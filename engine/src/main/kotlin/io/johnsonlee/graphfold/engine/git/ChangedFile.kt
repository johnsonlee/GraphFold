package io.johnsonlee.graphfold.engine.git

import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.diff.EditList

/** Native tree metadata and zero-based, half-open edits; source locations are derived at attribution. */
internal data class ChangedFile(
    val entry: DiffEntry,
    val edits: EditList,
    val binary: Boolean = false,
) {
    val basePath: String? get() = entry.oldPath.takeUnless { it == DiffEntry.DEV_NULL }
    val headPath: String? get() = entry.newPath.takeUnless { it == DiffEntry.DEV_NULL }
    val changeType: DiffEntry.ChangeType get() = entry.changeType
    val similarityScore: Int? get() = entry.score.takeIf {
        changeType == DiffEntry.ChangeType.RENAME || changeType == DiffEntry.ChangeType.COPY
    }
}
