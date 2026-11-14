package io.johnsonlee.graphfold.engine.git

import io.johnsonlee.graphfold.engine.Revision
import io.johnsonlee.graphfold.engine.SourceRange
import io.johnsonlee.graphfold.engine.source.DiffLocations
import org.eclipse.jgit.diff.Edit
import org.eclipse.jgit.diff.DiffEntry.ChangeType
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
            assertEquals(setOf(ChangeType.RENAME, ChangeType.COPY), changes.map { it.changeType }.toSet())
            assertEquals(setOf("first.txt", "second.txt"), changes.map { it.headPath }.toSet())
            assertTrue(changes.all { it.basePath == "original.txt" && it.similarityScore == 100 && it.edits.isEmpty() })
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
            assertEquals(listOf(ChangeType.DELETE, ChangeType.ADD), changes.map { it.changeType })
            val removed = changes.first()
            val added = changes.last()
            assertEquals("item", removed.basePath)
            assertEquals(null, removed.headPath)
            assertEquals("item", added.headPath)
            assertEquals(null, added.basePath)
            assertEquals(listOf(Edit(0, 1, 0, 0)), removed.edits)
            assertEquals(listOf(Edit(0, 0, 0, 1)), added.edits)
        }
    }

    @Test fun `native edits retain beginning and end insertions and convert source lines only at attribution`() {
        Git.init().setDirectory(directory.toFile()).call().use { git ->
            val before = "middle\n"
            val after = "first\nmiddle\nlast\n"
            Files.writeString(directory.resolve("Example.java"), before)
            git.add().addFilepattern(".").call()
            val base = commit(git)
            Files.writeString(directory.resolve("Example.java"), after)
            git.add().addFilepattern(".").call()
            val head = commit(git)
            val inserted = diff(git.repository.directory.toPath(), base, head).single()
            assertEquals(listOf(Edit(0, 0, 0, 1), Edit(1, 1, 2, 3)), inserted.edits)
            assertTrue(inserted.edits.all { it.lengthA == 0 })
            val additions = DiffLocations.locations(inserted, before, after)
            assertEquals(listOf(Revision.HEAD, Revision.HEAD), additions.map { it.revision })
            assertEquals(listOf(SourceRange(1, 1), SourceRange(3, 3)), additions.map { it.line })
            val deleted = diff(git.repository.directory.toPath(), head, base).single()
            assertEquals(listOf(Edit(0, 1, 0, 0), Edit(2, 3, 1, 1)), deleted.edits)
            assertTrue(deleted.edits.all { it.lengthB == 0 })
            val removals = DiffLocations.locations(deleted, after, before)
            assertEquals(listOf(Revision.BASE, Revision.BASE), removals.map { it.revision })
            assertEquals(additions.map { it.line }, removals.map { it.line })
        }
    }

    private fun commit(git: Git): String = git.commit().setNoVerify(true).setMessage("diff fixture")
        .setAuthor("Fixture", "fixture@example.invalid").setCommitter("Fixture", "fixture@example.invalid").call().name
}
