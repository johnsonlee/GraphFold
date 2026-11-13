package io.johnsonlee.graphfold.engine.git

import java.nio.file.Files
import java.nio.file.Path
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.ObjectWalk
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.treewalk.TreeWalk
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocalCommitFetchTest {
    @TempDir lateinit var temporary: Path

    @Test fun `unadvertised detached commit imports ancestor trees and blobs without changing source refs or config`() {
        val source = temporary.resolve("source")
        Git.init().setDirectory(source.toFile()).call().use { git ->
            Files.writeString(source.resolve("removed.txt"), "ancestor-only content")
            git.add().addFilepattern(".").call()
            val base = git.commit().setMessage("base").setAuthor("Fixture", "fixture@example.invalid").call()
            git.checkout().setName(base.name).call()
            git.rm().addFilepattern("removed.txt").call()
            Files.createDirectories(source.resolve("nested"))
            Files.writeString(source.resolve("nested/current.txt"), "detached content")
            git.add().addFilepattern(".").call()
            val detached = git.commit().setMessage("detached").setAuthor("Fixture", "fixture@example.invalid").call()
            git.checkout().setName(base.name).call()
            val config = Files.readAllBytes(git.repository.directory.toPath().resolve("config"))
            val refs = git.repository.refDatabase.getRefs().associate { it.name to it.objectId }
            val head = git.repository.resolve(Constants.HEAD)
            Git.init().setBare(true).setDirectory(temporary.resolve("cache.git").toFile()).call().use { cache ->
                assertFalse(cache.repository.objectDatabase.has(detached))
                fetch(cache.repository, source.toString(), detached.name)
                fetch(cache.repository, source.toUri().toString(), detached.name)
                assertTrue(cache.repository.objectDatabase.has(detached))
                assertImportedHistory(cache.repository, base, detached)
            }
            assertEquals(refs, git.repository.refDatabase.getRefs().associate { it.name to it.objectId })
            assertEquals(head, git.repository.resolve(Constants.HEAD))
            assertContentEquals(config, Files.readAllBytes(git.repository.directory.toPath().resolve("config")))
        }
    }

    private fun assertImportedHistory(repository: Repository, base: ObjectId, detached: ObjectId) {
        RevWalk(repository).use { walk ->
            val imported = walk.parseCommit(detached)
            assertEquals(base, imported.parents.single().id)
            assertBlob(repository, imported.tree, "nested/current.txt", "detached content")
            assertBlob(repository, walk.parseCommit(base).tree, "removed.txt", "ancestor-only content")
        }
        ObjectWalk(repository).use { walk ->
            walk.markStart(walk.parseCommit(detached))
            val commits = generateSequence { walk.next() }.map { it.copy() }.toSet()
            assertEquals(setOf(base, detached), commits)
            generateSequence { walk.nextObject() }.forEach { assertTrue(repository.objectDatabase.has(it)) }
        }
    }

    private fun assertBlob(repository: Repository, tree: ObjectId, path: String, expected: String) {
        TreeWalk.forPath(repository, path, tree).use { entry ->
            assertEquals(expected, String(repository.open(entry.getObjectId(0)).bytes))
        }
    }

}
