package io.johnsonlee.graphfold.engine.git

import java.nio.file.Path
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.ProgressMonitor
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.transport.FetchConnection
import org.eclipse.jgit.transport.FetchResult
import org.eclipse.jgit.transport.PushConnection
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.SshTransport
import org.eclipse.jgit.transport.TagOpt
import org.eclipse.jgit.transport.Transport
import org.eclipse.jgit.transport.URIish
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class FetchFailureTest {
    @TempDir lateinit var directory: Path

    @Test fun `scp transport keeps JVM SSH setup and closes after a failed fetch`() = repository { repository ->
        val failure = IllegalStateException("fixture failure")
        val transport = object : SshTransport(repository, URIish("host:repository")) {
            var closed = false
            override fun openFetch(): FetchConnection = error("Unexpected connection")
            override fun openPush(): PushConnection = error("Unexpected push")
            override fun fetch(monitor: ProgressMonitor, refs: Collection<RefSpec>): FetchResult? {
                assertNotNull(sshSessionFactory)
                assertEquals(TagOpt.NO_TAGS, tagOpt)
                assertEquals(null, credentialsProvider)
                assertEquals(listOf("refs/heads/main"), refs.map(RefSpec::toString))
                throw failure
            }
            override fun close() { closed = true; super.close() }
        }
        val caught = assertFailsWith<IllegalStateException> {
            fetch(repository, "host:repository", "refs/heads/main", openTransport = { _, _ -> transport })
        }
        assertSame(failure, caught)
        assertTrue(transport.closed)
    }

    @Test fun `elapsed deadline rejects an otherwise successful transport result and closes it`() = repository { repository ->
        var now = 0L
        val transport = StubTransport(repository) { monitor ->
            assertFalse(monitor.isCancelled)
            now = DEADLINE_NANOS
            assertTrue(monitor.isCancelled)
        }
        val failure = assertFailsWith<GitOperationException> {
            fetch(repository, "https://fixture.invalid/repo", "refs/heads/main", { _, _ -> transport }, { now })
        }
        assertEquals("Git operation timed out", failure.message)
        assertTrue(transport.closed)
    }

    @Test fun `deadline survives monotonic clock signed rollover`() = repository { repository ->
        val started = Long.MAX_VALUE - DEADLINE_NANOS / 2
        var now = started
        val transport = StubTransport(repository) { monitor ->
            assertFalse(monitor.isCancelled)
            now += DEADLINE_NANOS - 1
            assertFalse(monitor.isCancelled)
            now += 1
            assertTrue(monitor.isCancelled)
        }
        val failure = assertFailsWith<GitOperationException> {
            fetch(repository, "https://fixture.invalid/repo", "refs/heads/main", { _, _ -> transport }, { now })
        }
        assertEquals("Git operation timed out", failure.message)
        assertTrue(transport.closed)
    }

    @Test fun `interrupting transport is cancellation even when transport returns normally`() = repository { repository ->
        val transport = StubTransport(repository) { monitor ->
            monitor.start(1)
            monitor.beginTask("fixture", 1)
            monitor.update(1)
            monitor.endTask()
            monitor.showDuration(true)
            assertFalse(monitor.isCancelled)
            Thread.currentThread().interrupt()
            assertTrue(monitor.isCancelled)
        }
        try {
            assertFailsWith<InterruptedException> {
                fetch(repository, "https://fixture.invalid/repo", "refs/heads/main", { _, _ -> transport }, { 0L })
            }
            assertTrue(Thread.currentThread().isInterrupted)
            assertTrue(transport.closed)
        } finally {
            Thread.interrupted()
        }
    }

    private class StubTransport(repository: Repository, private val action: (ProgressMonitor) -> Unit) :
        Transport(repository, URIish("https://fixture.invalid/repo")) {
        var closed = false
        override fun openFetch(): FetchConnection = error("Unexpected connection")
        override fun openPush(): PushConnection = error("Unexpected push")
        override fun fetch(monitor: ProgressMonitor, refs: Collection<RefSpec>): FetchResult? {
            action(monitor)
            return null
        }
        override fun close() { closed = true }
    }

    private fun repository(action: (Repository) -> Unit) {
        Git.init().setBare(true).setDirectory(directory.resolve("repo.git").toFile()).call().use { action(it.repository) }
    }

    private companion object {
        const val DEADLINE_NANOS = 300_000_000_000L
    }
}
