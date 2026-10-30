package io.johnsonlee.graphfold.engine.git

import java.nio.file.Files
import java.nio.file.Path
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.CommitBuilder
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.TreeFormatter
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CheckoutTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `raw tree cannot overwrite checkout metadata`() {
        Git.init().setBare(true).setDirectory(temporary.resolve("objects.git").toFile()).call().use { git ->
            val commit = git.repository.newObjectInserter().use { objects ->
                val blob = objects.insert(Constants.OBJ_BLOB, "malicious metadata".toByteArray())
                val tree = objects.insert(TreeFormatter().apply { append(".git", FileMode.REGULAR_FILE, blob) })
                val identity = PersonIdent("Fixture", "fixture@example.invalid")
                val id = objects.insert(CommitBuilder().apply {
                    setTreeId(tree)
                    author = identity
                    committer = identity
                    message = "unsafe checkout tree"
                })
                objects.flush()
                id
            }
            val destination = temporary.resolve("checkout")
            val failure = assertFailsWith<GitOperationException> {
                checkout(git.repository, commit.name, destination)
            }
            assertEquals("Unsafe checkout path", failure.message)
            assertTrue(Files.isDirectory(destination.resolve(".git")))
            assertTrue(Files.readString(destination.resolve(".git/HEAD")).startsWith("ref: refs/heads/"))
        }
    }
}
