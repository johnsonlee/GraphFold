package io.johnsonlee.graphfold.engine.git

import java.nio.file.Files
import java.nio.file.Path
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Constants
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RevisionResolutionTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `full SHA import does not fetch unrelated branch objects`() {
        val source = temporary.resolve("source")
        Git.init().setDirectory(source.toFile()).call().use { git ->
            val base = commit(git, source, "base")
            val head = commit(git, source, "head")
            git.checkout().setCreateBranch(true).setName("unrelated").setStartPoint(base).call()
            val unrelated = commit(git, source, "unrelated")
            GitWorkspaceManager(temporary.resolve("cache")).open(source.toString(), base, head).use { workspace ->
                Git.open(workspace.headDirectory.toFile()).use { checkout ->
                    assertFalse(checkout.repository.objectDatabase.has(org.eclipse.jgit.lib.ObjectId.fromString(unrelated)))
                    assertTrue(checkout.repository.objectDatabase.has(org.eclipse.jgit.lib.ObjectId.fromString(head)))
                }
            }
        }
    }

    @Test
    fun `ambiguous and noncommit abbreviations give safe actionable diagnostics`() {
        Git.init().setBare(true).setDirectory(temporary.resolve("objects.git").toFile()).call().use { git ->
            git.repository.newObjectInserter().use { objects ->
                // Fixed SHA-1 blob-prefix collision avoids probabilistic searches in the test.
                objects.insert(Constants.OBJ_BLOB, "20738".toByteArray())
                objects.insert(Constants.OBJ_BLOB, "37901".toByteArray())
                objects.flush()
            }
            val ambiguous = assertFailsWith<GitOperationException> { resolveCommit(git.repository, "65ba8ca") }
            assertEquals("Commit abbreviation is ambiguous; provide the full 40-character commit SHA", ambiguous.message)
            assertNull(ambiguous.cause)
            val noncommit = assertFailsWith<GitOperationException> { resolveCommit(git.repository, "65ba8cae") }
            assertEquals("Revision identifies a non-commit object; provide a commit SHA", noncommit.message)
            assertNull(noncommit.cause)
            assertNull(resolveCommit(git.repository, "0000000000000000000000000000000000000000"))
            assertNull(resolveCommit(git.repository, "0000000"))
        }
    }

    private fun commit(git: Git, source: Path, text: String): String {
        Files.writeString(source.resolve("Example.java"), text)
        git.add().addFilepattern(".").call()
        return git.commit().setNoVerify(true).setMessage(text)
            .setAuthor("Fixture", "fixture@example.invalid").setCommitter("Fixture", "fixture@example.invalid").call().name
    }
}
