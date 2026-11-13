package io.johnsonlee.graphfold.engine.git

import java.nio.file.Files
import java.nio.file.Path
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.dircache.DirCacheCheckout
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.lib.ProgressMonitor
import org.eclipse.jgit.lib.NullProgressMonitor
import org.eclipse.jgit.lib.RefUpdate
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.treewalk.TreeWalk

/** JGit restores the index and working tree; private checkout configuration disables content filters. */
internal fun checkout(cached: Repository, revision: String, directory: Path) {
    checkGitCancellation()
    Files.createDirectories(directory)
    val gitDirectory = directory.resolve(".git")
    if (!Files.exists(gitDirectory.resolve("HEAD"))) {
        JGitRepositories.open(gitDirectory, directory).use { checkout -> checkout.create(false) }
    }
    val alternate = gitDirectory.resolve("objects/info/alternates")
    Files.createDirectories(alternate.parent)
    Files.writeString(alternate, cached.directory.toPath().resolve("objects").toAbsolutePath().toString() + "\n")
    val info = Files.createDirectories(gitDirectory.resolve("info"))
    Files.writeString(info.resolve("attributes"), "* -filter -text -ident\n")
    val hooks = Files.createDirectories(gitDirectory.resolve("graphfold-empty-hooks"))
    // Reopen after persisting alternates so JGit and later build tools see the same cached objects.
    JGitRepositories.open(gitDirectory, directory).use { checkout ->
        checkout.config.apply {
            setBoolean("core", null, "autocrlf", false)
            setString("core", null, "hooksPath", hooks.toAbsolutePath().toString())
            save()
        }
        val commit = RevWalk(cached).use { it.parseCommit(cached.resolve(revision)) }
        validateCompleteInput(cached, commit.tree)
        val index = checkout.lockDirCache()
        try {
            val operation = DirCacheCheckout(checkout, index, commit.tree)
            operation.setFailOnConflict(false)
            operation.setForce(true)
            operation.setProgressMonitor(CheckoutProgress)
            check(operation.checkout()) { "Cannot restore every checkout path" }
        } finally {
            index.unlock()
        }
        checkGitCancellation()
        val result = checkout.updateRef(Constants.HEAD, true).apply {
            setNewObjectId(commit)
            isForceUpdate = true
        }.forceUpdate()
        if (result !in SUCCESSFUL_HEAD_UPDATES) throw GitOperationException("Cannot update checkout HEAD: $result")
        Git(checkout).clean().setCleanDirectories(true).setForce(true).call()
    }
}

/** Unsupported external content cannot silently become complete static input. This does not materialize Git objects. */
private fun validateCompleteInput(repository: Repository, treeId: org.eclipse.jgit.lib.ObjectId) {
    TreeWalk(repository).use { tree ->
        tree.addTree(treeId)
        tree.isRecursive = true
        while (tree.next()) {
            checkGitCancellation()
            if (tree.getFileMode(0) == FileMode.GITLINK) {
                throw GitOperationException("Submodule inputs are not supported; analysis cannot be complete")
            }
            rejectLfsPointer(repository, tree.getObjectId(0))
        }
    }
}

private fun rejectLfsPointer(repository: Repository, blob: org.eclipse.jgit.lib.ObjectId) {
    val prefix = repository.open(blob, Constants.OBJ_BLOB).openStream().use { it.readNBytes(LFS_PREFIX.length) }
    if (String(prefix, Charsets.UTF_8) == LFS_PREFIX) {
        throw GitOperationException("Git LFS inputs are not supported; analysis cannot be complete")
    }
}

private object CheckoutProgress : ProgressMonitor by NullProgressMonitor.INSTANCE {
    override fun isCancelled(): Boolean = Thread.currentThread().isInterrupted
}

private const val LFS_PREFIX = "version https://git-lfs.github.com/spec/v1"
private val SUCCESSFUL_HEAD_UPDATES = setOf(
    RefUpdate.Result.NEW, RefUpdate.Result.NO_CHANGE, RefUpdate.Result.FAST_FORWARD, RefUpdate.Result.FORCED,
)
