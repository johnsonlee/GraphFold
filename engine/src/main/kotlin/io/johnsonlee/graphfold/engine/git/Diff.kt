package io.johnsonlee.graphfold.engine.git

import org.eclipse.jgit.diff.DiffAlgorithm
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.diff.DiffFormatter
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
    return ChangedFile(entry, header.toEditList(), header.patchType == FileHeader.PatchType.BINARY)
}
