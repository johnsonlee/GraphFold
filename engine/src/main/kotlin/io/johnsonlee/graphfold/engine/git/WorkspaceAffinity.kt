package io.johnsonlee.graphfold.engine.git

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.revwalk.RevCommit

internal const val AFFINITY_HISTORY_LIMIT = 256
internal const val UNKNOWN_SAME_BASE_AFFINITY = 1_000_000
internal const val OTHER_WORKSPACE_AFFINITY = Int.MAX_VALUE

/** A bounded reuse heuristic. Checkout remains authoritative even when prior HEAD hints match exactly. */
internal class WorkspaceAffinity(private val repository: Repository, private val base: String, private val head: String) {
    private var requestedAncestors: Map<String, Int>? = null

    /** Called only while holding the candidate lease. Zero means an exact pair and needs no history walk. */
    fun score(slot: Path, hasTime: () -> Boolean): Int {
        checkGitCancellation()
        return if (previousHead(slot.resolve("base")) != base) OTHER_WORKSPACE_AFFINITY else {
            scoreHead(previousHead(slot.resolve("head")), hasTime)
        }
    }

    private fun scoreHead(previous: String?, hasTime: () -> Boolean): Int = when (previous) {
        null -> UNKNOWN_SAME_BASE_AFFINITY
        head -> 0
        else -> {
            val requested = requestedAncestors ?: ancestors(head, hasTime).also { requestedAncestors = it }
            val existing = ancestors(previous, hasTime)
            val distance = existing.mapNotNull { (commit, depth) -> requested[commit]?.plus(depth) }.minOrNull()
            distance?.plus(1) ?: UNKNOWN_SAME_BASE_AFFINITY
        }
    }

    private fun previousHead(directory: Path): String? {
        val metadata = directory.resolve(".git")
        if (!Files.isRegularFile(metadata.resolve("HEAD"))) return null
        return try {
            JGitRepositories.open(metadata, directory).use { it.resolve(Constants.HEAD)?.name }
        } catch (_: IOException) {
            null // Missing or damaged hints must not prevent a normal checkout from repairing the slot.
        }
    }

    /** Minimum observed common-ancestor distance; the bounded neighborhood is not a global shortest-path proof. */
    private fun ancestors(revision: String, hasTime: () -> Boolean): Map<String, Int> {
        return try {
            RevWalk(repository).use { walk -> walkAncestors(walk, revision, hasTime) }
        } catch (_: IOException) {
            emptyMap()
        }
    }

    private fun walkAncestors(walk: RevWalk, revision: String, hasTime: () -> Boolean): Map<String, Int> {
        val distances = linkedMapOf(revision to 0)
        val pending = ArrayDeque<String>().apply { add(revision) }
        while (pending.isNotEmpty()) {
            checkGitCancellation()
            if (!hasTime()) break
            val current = pending.removeFirst()
            val commit = walk.parseCommit(ObjectId.fromString(current))
            enqueueParents(commit, distances, pending)
        }
        return distances
    }

    private fun enqueueParents(commit: RevCommit, distances: MutableMap<String, Int>, pending: ArrayDeque<String>) {
        val nextDepth = distances.getValue(commit.name) + 1
        for (parent in commit.parents) {
            if (distances.size >= AFFINITY_HISTORY_LIMIT) break
            if (parent.name !in distances) {
                distances[parent.name] = nextDepth
                pending.add(parent.name)
            }
        }
    }

}
