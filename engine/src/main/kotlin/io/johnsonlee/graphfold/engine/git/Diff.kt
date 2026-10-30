package io.johnsonlee.graphfold.engine.git

import org.eclipse.jgit.diff.DiffAlgorithm
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.diff.Edit
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.patch.FileHeader
import org.eclipse.jgit.revwalk.RevWalk
import java.io.OutputStream
import java.nio.file.Path

/** Read tree changes directly; external diff drivers, textconv and shell commands are never invoked. */
internal fun diff(repository: Path, base: String, head: String): List<ChangedFile> = gitOperation {
    JGitRepositories.open(repository).use { repo -> compare(repo, base, head) }
}

private fun compare(repo: org.eclipse.jgit.lib.Repository, base: String, head: String): List<ChangedFile> {
    val trees = RevWalk(repo).use { walk ->
        walk.parseCommit(ObjectId.fromString(base)).tree to walk.parseCommit(ObjectId.fromString(head)).tree
    }
    return DiffFormatter(OutputStream.nullOutputStream()).use { formatter ->
        formatter.setRepository(repo)
        formatter.isDetectRenames = true
        formatter.setDiffAlgorithm(DiffAlgorithm.getAlgorithm(DiffAlgorithm.SupportedAlgorithm.MYERS))
        formatter.setContext(0)
        formatter.scan(trees.first, trees.second).map { changed(it, formatter) }
    }
}

private fun changed(entry: DiffEntry, formatter: DiffFormatter): ChangedFile {
    checkGitCancellation()
    val header = formatter.toFileHeader(entry)
    val edits = header.toEditList()
    return ChangedFile(
        entry.oldPath.takeUnless { it == DiffEntry.DEV_NULL },
        entry.newPath.takeUnless { it == DiffEntry.DEV_NULL },
        edits.mapNotNull { range(it.beginA, it.endA) },
        edits.mapNotNull { range(it.beginB, it.endB) },
        header.patchType == FileHeader.PatchType.BINARY,
        status(entry), edits.map(::hunk),
    )
}

private fun range(begin: Int, end: Int): ChangedLineRange? =
    if (begin == end) null else ChangedLineRange(begin + 1, end)

private fun hunk(edit: Edit) = ChangedHunk(
    if (edit.lengthA == 0) edit.beginA else edit.beginA + 1, edit.lengthA,
    if (edit.lengthB == 0) edit.beginB else edit.beginB + 1, edit.lengthB,
)

private fun status(entry: DiffEntry): String = when (entry.changeType) {
    DiffEntry.ChangeType.ADD -> "A"
    DiffEntry.ChangeType.DELETE -> "D"
    DiffEntry.ChangeType.RENAME -> "R${entry.score}"
    DiffEntry.ChangeType.COPY -> "C${entry.score}"
    DiffEntry.ChangeType.MODIFY -> if ((entry.oldMode.bits and TYPE_MASK) != (entry.newMode.bits and TYPE_MASK)) "T" else "M"
}

private const val TYPE_MASK = 0xF000
