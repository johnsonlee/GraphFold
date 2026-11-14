package io.johnsonlee.graphfold.engine.git

import java.nio.file.Path
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.ObjectInserter
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.ObjectWalk
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.transport.URIish

/** Import a detached local commit without altering the source's refs or upload-pack policy. */
internal object JGitLocalObjects {
    fun importCommit(cached: Repository, uri: URIish, revision: String) {
        FileRepositoryBuilder().setFS(cached.fs).findGitDir(Path.of(uri.path).toFile())
            .setMustExist(true).build().use { source ->
                val id = source.resolve(revision) ?: throw GitOperationException("Commit is unavailable")
                importObjects(cached, source, id)
            }
    }

    private fun importObjects(cached: Repository, source: Repository, id: ObjectId) {
        ObjectWalk(source).use { walk ->
            walk.markStart(walk.parseCommit(id))
            cached.newObjectInserter().use { inserter ->
                // JGit owns ancestor and object traversal, deduplication, and gitlink handling.
                generateSequence { walk.next() }.forEach { copyMissing(cached, source, inserter, it) }
                generateSequence { walk.nextObject() }.forEach { copyMissing(cached, source, inserter, it) }
                inserter.flush()
            }
        }
    }

    private fun copyMissing(cached: Repository, source: Repository, inserter: ObjectInserter, id: ObjectId) {
        checkGitCancellation()
        if (cached.objectDatabase.has(id)) return
        val loader = source.open(id)
        loader.openStream().use { inserter.insert(loader.type, loader.size, it) }
    }
}
