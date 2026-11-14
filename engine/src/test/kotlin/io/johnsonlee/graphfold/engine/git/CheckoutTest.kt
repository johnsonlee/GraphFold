package io.johnsonlee.graphfold.engine.git

import java.nio.file.Files
import java.nio.file.Path
import org.eclipse.jgit.dircache.InvalidPathException
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.CommitBuilder
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.TreeFormatter
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.junit.jupiter.api.io.TempDir
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CheckoutTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `native checkout restores raw bytes without running configured filters`() {
        val source = Files.createDirectory(temporary.resolve("source"))
        Git.init().setDirectory(source.toFile()).call().use { git ->
            Files.writeString(source.resolve(".gitattributes"), "* filter=external text eol=crlf ident\n")
            Files.writeString(source.resolve("payload"), "first\nsecond\n${'$'}Id${'$'}\n")
            git.add().addFilepattern(".").call()
            val revision = git.commit().setMessage("filter fixture").setAuthor("Fixture", "fixture@example.invalid")
                .setCommitter("Fixture", "fixture@example.invalid").call().name
            val destination = temporary.resolve("checkout")
            checkout(git.repository, revision, destination)
            Git.open(destination.toFile()).use { restored ->
                restored.repository.config.apply {
                    setString("filter", "external", "smudge", "exit 73")
                    setString("filter", "external", "clean", "exit 74")
                    setBoolean("filter", "external", "required", true)
                    setBoolean("core", null, "autocrlf", true)
                    save()
                }
            }
            Files.writeString(destination.resolve("payload"), "dirty")
            checkout(git.repository, revision, destination)
            assertEquals("first\nsecond\n${'$'}Id${'$'}\n", Files.readString(destination.resolve("payload")))
        }
    }

    @ParameterizedTest
    @ValueSource(strings = [".git", ".GiT"])
    fun `native checkout cannot overwrite checkout metadata`(metadataName: String) {
        Git.init().setBare(true).setDirectory(temporary.resolve("objects.git").toFile()).call().use { git ->
            val commit = git.repository.newObjectInserter().use { objects ->
                val blob = objects.insert(Constants.OBJ_BLOB, "malicious metadata".toByteArray())
                val tree = objects.insert(TreeFormatter().apply { append(metadataName, FileMode.REGULAR_FILE, blob) })
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
            assertFailsWith<InvalidPathException> {
                checkout(git.repository, commit.name, destination)
            }
            assertTrue(Files.isDirectory(destination.resolve(".git")))
            assertTrue(Files.readString(destination.resolve(".git/HEAD")).startsWith("ref: refs/heads/"))
        }
    }

    @Test
    fun `malformed tree traversal cannot write outside the checkout`() {
        Git.init().setBare(true).setDirectory(temporary.resolve("objects.git").toFile()).call().use { git ->
            val commit = git.repository.newObjectInserter().use { objects ->
                val blob = objects.insert(Constants.OBJ_BLOB, "outside checkout".toByteArray())
                val name = "100644 ../escape\u0000".toByteArray()
                val bytes = name + ByteArray(Constants.OBJECT_ID_LENGTH).also { blob.copyRawTo(it, 0) }
                val tree = objects.insert(Constants.OBJ_TREE, bytes)
                val identity = PersonIdent("Fixture", "fixture@example.invalid")
                val id = objects.insert(CommitBuilder().apply {
                    setTreeId(tree)
                    author = identity
                    committer = identity
                    message = "malformed traversal tree"
                })
                objects.flush()
                id
            }
            assertFailsWith<InvalidPathException> {
                checkout(git.repository, commit.name, temporary.resolve("checkout"))
            }
            assertFalse(Files.exists(temporary.resolve("escape")))
        }
    }
}
