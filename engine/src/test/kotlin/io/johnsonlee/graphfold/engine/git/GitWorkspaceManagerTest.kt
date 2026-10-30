package io.johnsonlee.graphfold.engine.git

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ResetCommand
import org.eclipse.jgit.dircache.DirCacheEntry
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.lib.ObjectId
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GitWorkspaceManagerTest {
    @TempDir
    lateinit var temporary: Path
    private lateinit var repository: Path
    private lateinit var manager: GitWorkspaceManager

    @BeforeEach
    fun setup() {
        repository = Files.createDirectory(temporary.resolve("source repo"))
        Git.init().setDirectory(repository.toFile()).call().use { git ->
            git.repository.config.apply {
                setString("core", null, "hooksPath", Files.createDirectory(temporary.resolve("empty-hooks")).toString())
                setString("user", null, "name", "Fixture")
                setString("user", null, "email", "fixture@example.invalid")
                save()
            }
        }
        manager = GitWorkspaceManager(temporary.resolve("cache"))
    }

    @Test
    fun `checks out exact commits without changing user checkout and cleans up`() {
        write("Example.java", "one\ntwo\nthree\n")
        val base = commit()
        write("Example.java", "one\nchanged\nthree\nfour\n")
        val head = commit()
        write("untracked.txt", "user data")
        val workspace = manager.open(repository.toString(), base, head)
        assertEquals(base, workspace.baseSha)
        assertEquals(head, workspace.headSha)
        assertEquals("one\ntwo\nthree\n", Files.readString(workspace.baseDirectory.resolve("Example.java")))
        val change = workspace.changes().single()
        assertEquals(listOf(ChangedLineRange(2, 2)), change.baseLines)
        assertEquals(listOf(ChangedLineRange(2, 2), ChangedLineRange(4, 4)), change.headLines)
        assertEquals("Example.java", change.basePath)
        assertFalse(change.binary)
        workspace.close()
        workspace.close()
        assertFalse(Files.exists(workspace.baseDirectory))
        assertFalse(Files.exists(workspace.headDirectory))
        assertEquals(head, head())
        assertEquals("user data", Files.readString(repository.resolve("untracked.txt")))
        assertFailsWith<IllegalStateException> { workspace.changes() }
    }

    @Test
    fun `tracks additions deletions renames and unusual names without quoting`() {
        write("old.java", "original\n")
        write("deleted.java", "deleted\n")
        val base = commit()
        Files.move(repository.resolve("old.java"), repository.resolve("renamed\nfile.java"))
        Files.delete(repository.resolve("deleted.java"))
        write("new\tfile.java", "added\nsecond\n")
        val head = commit()
        manager.open(repository.toString(), base, head).use { workspace ->
            val changes = workspace.changes()
            val renamed = changes.single { it.changeType.startsWith("R") }
            assertEquals("old.java", renamed.basePath)
            assertEquals("renamed\nfile.java", renamed.headPath)
            assertTrue(renamed.baseLines.isEmpty())
            val added = changes.single { it.changeType == "A" }
            assertNull(added.basePath)
            assertEquals(listOf(ChangedLineRange(1, 2)), added.headLines)
            assertTrue(added.baseLines.isEmpty())
            val deleted = changes.single { it.changeType == "D" }
            assertNull(deleted.headPath)
            assertEquals(listOf(ChangedLineRange(1, 1)), deleted.baseLines)
            assertTrue(deleted.headLines.isEmpty())
        }
    }

    @Test
    fun `binary and empty files remain in change inventory`() {
        write("empty", "")
        val base = commit()
        Files.write(repository.resolve("binary"), byteArrayOf(0, 1, 2, 3))
        Files.delete(repository.resolve("empty"))
        val head = commit()
        manager.open(repository.toString(), base, head).use {
            assertTrue(it.changes().single { change -> change.headPath == "binary" }.binary)
            assertEquals("empty", it.changes().single { change -> change.changeType == "D" }.basePath)
        }
    }

    @Test
    fun `same commit yields no changes and separate detached worktrees`() {
        write("file", "content")
        val revision = commit()
        manager.open(repository.toString(), revision.take(8), revision).use {
            assertTrue(it.changes().isEmpty())
            assertNotEquals(it.baseDirectory, it.headDirectory)
            assertEquals(revision, it.baseSha)
        }
    }

    @Test
    fun `concurrent analyses reuse repository but isolate worktrees`() {
        write("file", "content")
        val revision = commit()
        val executor = Executors.newFixedThreadPool(2)
        try {
            val runs = (1..2).map {
                executor.submit<GitWorkspace> { manager.open(repository.toString(), revision, revision) }
            }.map { it.get() }
            try {
                assertNotEquals(runs[0].baseDirectory, runs[1].baseDirectory)
                runs[0].close()
                assertTrue(Files.exists(runs[1].baseDirectory))
            } finally {
                runs.forEach(GitWorkspace::close)
            }
            Files.list(temporary.resolve("cache/repositories")).use {
                assertEquals(1L, it.filter { path -> path.toString().endsWith(".git") }.count())
            }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `checkouts share cached objects and preserve symbolic links`() {
        write("target", "content")
        Files.createSymbolicLink(repository.resolve("link"), Path.of("target"))
        val revision = commit()
        manager.open(repository.toString(), revision, revision).use { workspace ->
            val alternates = listOf(workspace.baseDirectory, workspace.headDirectory).map { checkout ->
                assertTrue(Files.isSymbolicLink(checkout.resolve("link")))
                assertEquals(Path.of("target"), Files.readSymbolicLink(checkout.resolve("link")))
                val objects = checkout.resolve(".git/objects")
                Files.walk(objects).use { paths ->
                    assertTrue(paths.filter(Files::isRegularFile).allMatch { it.fileName.toString() == "alternates" })
                }
                Files.readString(objects.resolve("info/alternates")).trim()
            }
            assertEquals(alternates[0], alternates[1])
            assertTrue(Path.of(alternates[0]).startsWith(temporary.resolve("cache/repositories")))
            Git.open(workspace.headDirectory.toFile()).use {
                assertEquals(revision, it.repository.resolve("HEAD").name)
                assertFalse(it.repository.exactRef("HEAD").isSymbolic)
            }
            Files.writeString(workspace.headDirectory.resolve("target"), "analysis changes")
            assertEquals("content", Files.readString(workspace.baseDirectory.resolve("target")))
            assertEquals("content", Files.readString(repository.resolve("target")))
        }
    }

    @Test
    fun `fetches new commits into existing cache`() {
        write("file", "before")
        val base = commit()
        manager.open(repository.toString(), base, base).close()
        write("file", "after")
        val head = commit()
        manager.open(repository.toString(), base, head).use { assertEquals(1, it.changes().size) }
    }

    @Test
    fun `rejects unsupported LFS and cleans both worktrees`() {
        write("file", "before")
        val base = commit()
        write("large.bin", "version https://git-lfs.github.com/spec/v1\noid sha256:123\nsize 42\n")
        val head = commit()
        assertFailsWith<GitOperationException> { manager.open(repository.toString(), base, head) }
        Files.list(temporary.resolve("cache/runs")).use { assertEquals(0L, it.count()) }
    }

    @Test
    fun `rejects submodule trees`() {
        write("file", "before")
        val base = commit()
        updateIndex("submodule", FileMode.GITLINK, base)
        commitIndex("submodule")
        val head = head()
        assertFailsWith<GitOperationException> { manager.open(repository.toString(), base, head) }
        Files.list(temporary.resolve("cache/runs")).use { assertEquals(0L, it.count()) }
    }

    @Test
    fun `rejects invalid inputs and unavailable revisions`() {
        assertFailsWith<IllegalArgumentException> { manager.open("", "main", "main") }
        assertFailsWith<IllegalArgumentException> { manager.open(repository.toString(), "--help", "1234567") }
        write("file", "before")
        val base = commit()
        assertFailsWith<GitOperationException> { manager.open(repository.toString(), base, "0123456789abcdef") }
        assertFailsWith<IllegalArgumentException> { ChangedLineRange(0, 1) }
        assertFailsWith<IllegalArgumentException> { ChangedLineRange(2, 1) }
    }

    @Test
    fun `interruption is preserved for cancellation`() {
        write("file", "content")
        val revision = commit()
        Thread.currentThread().interrupt()
        try {
            assertFailsWith<InterruptedException> { manager.open(repository.toString(), revision, revision) }
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `unreachable commit can be fetched by its full SHA`() {
        write("file", "first")
        val base = commit()
        write("file", "second")
        val head = commit()
        Git.open(repository.toFile()).use { it.reset().setMode(ResetCommand.ResetType.HARD).setRef(base).call() }
        manager.open(repository.toString(), base, head).use { assertEquals(head, it.headSha) }
    }

    @Test
    fun `mode only changes remain visible`() {
        write("script", "echo hello\n")
        val base = commit()
        updateIndex("script", FileMode.EXECUTABLE_FILE)
        commitIndex("mode")
        val head = head()
        manager.open(repository.toString(), base, head).use {
            val change = it.changes().single()
            assertEquals("script", change.headPath)
            assertTrue(change.headLines.isEmpty())
            assertTrue(Files.isExecutable(it.headDirectory.resolve("script")))
        }
    }

    @Test
    fun `URL repositories resolve without interpreting them as local directories`() {
        write("file", "content")
        val revision = commit()
        manager.open(repository.toUri().toString(), revision, revision).use { assertTrue(it.changes().isEmpty()) }
        assertFailsWith<IllegalArgumentException> { manager.open("--help", revision, revision) }
        assertFailsWith<IllegalArgumentException> { manager.open(repository.toString(), revision, "main") }
        deleteDirectory(temporary.resolve("does-not-exist"))
    }

    @Test
    fun `broken cache initialization is removed and can be retried`() {
        write("file", "content")
        val revision = commit()
        val key = java.security.MessageDigest.getInstance("SHA-256").digest(repository.toRealPath().toString().toByteArray())
            .joinToString("") { "%02x".format(it) }
        val bare = temporary.resolve("cache/repositories/$key.git")
        Files.createDirectories(bare.resolve("config"))
        assertFailsWith<GitOperationException> { manager.open(repository.toString(), revision, revision) }
        assertFalse(Files.exists(bare))
        manager.open(repository.toString(), revision, revision).close()
    }

    private fun write(path: String, text: String) { Files.writeString(repository.resolve(path), text) }

    private fun commit(): String = Git.open(repository.toFile()).use { git ->
        git.add().addFilepattern(".").call()
        git.add().addFilepattern(".").setUpdate(true).call()
        git.commit().setNoVerify(true).setMessage("fixture").call().name
    }

    private fun commitIndex(message: String) {
        Git.open(repository.toFile()).use { it.commit().setNoVerify(true).setMessage(message).call() }
    }

    private fun head(): String = Git.open(repository.toFile()).use { it.repository.resolve("HEAD").name }

    private fun updateIndex(path: String, mode: FileMode, objectId: String? = null) {
        Git.open(repository.toFile()).use { git ->
            val index = git.repository.lockDirCache()
            try {
                val entries = (0 until index.entryCount).map(index::getEntry).filter { it.pathString != path }
                val entry = DirCacheEntry(path).apply {
                    fileMode = mode
                    setObjectId(objectId?.let(ObjectId::fromString) ?: index.getEntry(path).objectId)
                }
                val builder = index.builder()
                (entries + entry).sortedBy { it.pathString }.forEach(builder::add)
                assertTrue(builder.commit())
            } finally {
                index.unlock()
            }
        }
    }
}
