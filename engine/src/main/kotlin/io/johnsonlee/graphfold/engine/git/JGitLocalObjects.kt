package io.johnsonlee.graphfold.engine.git

import java.nio.file.Path
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.ObjectInserter
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.transport.URIish
import org.eclipse.jgit.treewalk.TreeWalk

/** Import a detached local commit without altering the source's refs or upload-pack policy. */
internal object JGitLocalObjects {
    fun importCommit(cached: Repository, uri: URIish, revision: String) {
        FileRepositoryBuilder().setFS(cached.fs).findGitDir(Path.of(uri.path).toFile())
            .setMustExist(true).build().use { source ->
                val id = source.resolve(revision) ?: throw GitOperationException("Commit is unavailable")
                RevWalk(source).use { it.parseCommit(id) }
                copyClosure(cached, source, id)
            }
    }

    private fun copyClosure(cached: Repository, source: Repository, root: ObjectId) {
        val pending = ArrayDeque<Pair<ObjectId, Boolean>>()
        val visited = mutableSetOf<ObjectId>()
        pending.add(root to false)
        cached.newObjectInserter().use { inserter ->
            while (pending.isNotEmpty()) {
                checkGitCancellation()
                val (id, expanded) = pending.removeLast()
                if (expanded) {
                    copyObject(source, inserter, id)
                } else if (visited.add(id) && !cached.objectDatabase.has(id)) {
                    pending.add(id to true)
                    pending.addAll(children(source, id, source.open(id).type).map { it to false })
                }
            }
            inserter.flush()
        }
    }

    private fun copyObject(source: Repository, inserter: ObjectInserter, id: ObjectId) {
        val loader = source.open(id)
        loader.openStream().use { inserter.insert(loader.type, loader.size, it) }
    }

    private fun children(source: Repository, id: ObjectId, type: Int): List<ObjectId> = when (type) {
        Constants.OBJ_COMMIT -> RevWalk(source).use { walk ->
            val commit = walk.parseCommit(id)
            listOf(commit.tree.copy()) + commit.parents.map { it.copy() }
        }
        Constants.OBJ_TREE -> TreeWalk(source).use { tree ->
            tree.addTree(id)
            buildList {
                while (tree.next()) {
                    if (tree.getFileMode(0) != FileMode.GITLINK) add(tree.getObjectId(0))
                }
            }
        }
        else -> emptyList()
    }
}
