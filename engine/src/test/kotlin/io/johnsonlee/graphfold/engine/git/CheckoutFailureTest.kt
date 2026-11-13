package io.johnsonlee.graphfold.engine.git

import java.nio.channels.ClosedChannelException
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.Files
import java.nio.file.Path
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.dircache.DirCacheCheckout
import org.mockito.Mockito
import org.eclipse.jgit.lib.CommitBuilder
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.lib.TreeFormatter
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CheckoutFailureTest {
    @TempDir lateinit var directory: Path

    @Test fun `cancelled materialization cleanup preserves interrupt and releases the failed slot`() {
        val pool = directory.resolve("pool")
        val lease = leaseWorkspace(pool, 1)
        val partial = Files.createDirectories(lease.directory).resolve("partial")
        Files.writeString(partial, "incomplete checkout")
        try {
            Thread.currentThread().interrupt()
            discardWorkspace(lease)
            assertTrue(Thread.currentThread().isInterrupted)
            assertFalse(Files.exists(lease.directory))
        } finally {
            Thread.interrupted()
        }
        leaseWorkspace(pool, 1).use { assertEquals(lease.directory, it.directory) }
    }

    @Test fun `HEAD lock failure rejects checkout and permits a later retry`() = repository { repository ->
        val base = commit(repository, FileMode.REGULAR_FILE)
        val head = commit(repository, FileMode.EXECUTABLE_FILE)
        val destination = directory.resolve("checkout")
        checkout(repository, base, destination)
        val lock = Files.writeString(destination.resolve(".git/HEAD.lock"), "locked")
        val failure = assertFailsWith<GitOperationException> { checkout(repository, head, destination) }
        assertEquals("Cannot update checkout HEAD: LOCK_FAILURE", failure.message)
        assertEquals(base, Files.readString(destination.resolve(".git/HEAD")).trim())
        assertFalse(Files.exists(destination.resolve(".git/index.lock")))
        Files.delete(lock)
        checkout(repository, head, destination)
        assertEquals(head, Files.readString(destination.resolve(".git/HEAD")).trim())
        assertTrue(Files.isExecutable(destination.resolve("file")))
    }

    @Test fun `incomplete native checkout cannot advance HEAD and releases index for retry`() = repository { repository ->
        val base = commit(repository, FileMode.REGULAR_FILE)
        val head = commit(repository, FileMode.EXECUTABLE_FILE)
        val destination = directory.resolve("checkout")
        checkout(repository, base, destination)
        Mockito.mockConstruction(DirCacheCheckout::class.java) { operation, _ ->
            Mockito.`when`(operation.checkout()).thenReturn(false)
        }.use { intercepted ->
            val failure = assertFailsWith<IllegalStateException> { checkout(repository, head, destination) }
            assertEquals("Cannot restore every checkout path", failure.message)
            assertEquals(1, intercepted.constructed().size)
            Mockito.verify(intercepted.constructed().single()).checkout()
            assertEquals(base, Files.readString(destination.resolve(".git/HEAD")).trim())
            assertFalse(Files.exists(destination.resolve(".git/index.lock")))
            assertEquals("payload", Files.readString(destination.resolve("file")))
        }
        checkout(repository, head, destination)
        assertEquals(head, Files.readString(destination.resolve(".git/HEAD")).trim())
        assertFalse(Files.exists(destination.resolve(".git/index.lock")))
        assertTrue(Files.isExecutable(destination.resolve("file")))
    }

    @Test fun `cleanup restores interrupt even when the lease lock cannot be released`() {
        val slot = Files.createDirectories(directory.resolve("slot"))
        val channel = FileChannel.open(directory.resolve("slot.lock"), CREATE, WRITE)
        val lock = channel.lock()
        var released = false
        val lease = WorkspaceLease(slot, channel, lock) { released = true }
        channel.close()
        try {
            Thread.currentThread().interrupt()
            assertFailsWith<ClosedChannelException> { discardWorkspace(lease) }
            assertTrue(Thread.currentThread().isInterrupted)
            assertTrue(released)
            assertFalse(Files.exists(slot))
        } finally {
            Thread.interrupted()
        }
    }

    private fun repository(action: (Repository) -> Unit) {
        Git.init().setBare(true).setDirectory(directory.resolve("repo.git").toFile()).call().use { action(it.repository) }
    }

    private fun commit(repository: Repository, mode: FileMode): String = repository.newObjectInserter().use { objects ->
        val blob = objects.insert(Constants.OBJ_BLOB, "payload".toByteArray())
        val tree = objects.insert(TreeFormatter().apply { append("file", mode, blob) })
        val identity = PersonIdent("Fixture", "fixture@example.invalid")
        val id = objects.insert(CommitBuilder().apply {
            setTreeId(tree)
            author = identity
            committer = identity
            message = "checkout failure fixture"
        })
        objects.flush()
        id.name
    }
}
