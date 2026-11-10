package io.johnsonlee.graphfold.engine.git

import java.nio.file.Files
import java.nio.file.Path
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.dircache.DirCacheEntry
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.lib.ObjectId
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DiffBoundaryTest {
    @TempDir lateinit var directory: Path

    @Test fun `one deleted source copied to two destinations records rename and copy provenance`() {
        Git.init().setDirectory(directory.toFile()).call().use { git ->
            Files.writeString(directory.resolve("original.txt"), "unchanged content\n")
            git.add().addFilepattern(".").call()
            val base = commit(git)
            Files.move(directory.resolve("original.txt"), directory.resolve("first.txt"))
            Files.copy(directory.resolve("first.txt"), directory.resolve("second.txt"))
            git.add().addFilepattern(".").call()
            git.add().setUpdate(true).addFilepattern(".").call()
            val changes = diff(git.repository.directory.toPath(), base, commit(git))
            assertEquals(setOf(GitChangeType.RENAME, GitChangeType.COPY), changes.map { it.changeType }.toSet())
            assertEquals(setOf("first.txt", "second.txt"), changes.map { it.headPath }.toSet())
            assertTrue(changes.all { it.basePath == "original.txt" && it.similarityScore == 100 && it.hunks.isEmpty() })
        }
    }

    @Test fun `file type transitions retain both deletion and addition evidence`() {
        Git.init().setDirectory(directory.toFile()).call().use { git ->
            Files.writeString(directory.resolve("item"), "target\n")
            git.add().addFilepattern(".").call()
            val base = commit(git)
            val index = git.repository.lockDirCache()
            try {
                val original = index.getEntry("item")
                index.builder().apply {
                    add(DirCacheEntry("item").apply {
                        fileMode = FileMode.SYMLINK
                        setObjectId(ObjectId.fromString(original.objectId.name))
                    })
                    finish()
                }
                index.write()
                check(index.commit())
            } finally {
                index.unlock()
            }
            val changes = diff(git.repository.directory.toPath(), base, commit(git))
            assertEquals(listOf(GitChangeType.DELETE, GitChangeType.ADD), changes.map { it.changeType })
            val removed = changes.first()
            val added = changes.last()
            assertEquals("item", removed.basePath)
            assertEquals(null, removed.headPath)
            assertEquals("item", added.headPath)
            assertEquals(null, added.basePath)
            assertEquals(listOf(ChangedLineRange(1, 1)), removed.baseLines)
            assertEquals(listOf(ChangedLineRange(1, 1)), added.headLines)
        }
    }

    private fun commit(git: Git): String = git.commit().setNoVerify(true).setMessage("diff fixture")
        .setAuthor("Fixture", "fixture@example.invalid").setCommitter("Fixture", "fixture@example.invalid").call().name
}
