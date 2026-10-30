package io.johnsonlee.graphfold.engine.git

import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.transport.Daemon
import org.eclipse.jgit.transport.DaemonClient
import org.eclipse.jgit.transport.resolver.FileResolver
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class GitRemoteTransportTest {
    @TempDir
    lateinit var temporary: Path

    @Test
    @Timeout(TEST_TIMEOUT_SECONDS)
    fun `fetches exact commits and refreshes cache over in process git protocol`() {
        val source = temporary.resolve("source")
        Git.init().setDirectory(source.toFile()).call().use { git ->
            git.repository.config.apply {
                setString("core", null, "hooksPath", Files.createDirectory(temporary.resolve("empty-hooks")).toString())
                save()
            }
            val base = commit(git, source, "before\n")
            val head = commit(git, source, "after\n")
            val daemon = Daemon(InetSocketAddress("127.0.0.1", 0)).apply {
                setRepositoryResolver(FileResolver<DaemonClient>().apply {
                    exportRepository("fixture.git", git.repository)
                })
                timeout = SOCKET_TIMEOUT_SECONDS
            }
            daemon.start()
            try {
                val remote = "git://127.0.0.1:${daemon.address.port}/fixture.git"
                val manager = GitWorkspaceManager(temporary.resolve("cache"))
                assertCheckout(manager, remote, base, head, "before\n", "after\n")
                // This object did not exist when the first fetch populated the shared cache.
                val next = commit(git, source, "new remote commit\n")
                assertCheckout(manager, remote, head, next, "after\n", "new remote commit\n")
                assertEquals(next, git.repository.resolve("HEAD").name)
                assertEquals("new remote commit\n", Files.readString(source.resolve("Example.java")))
            } finally {
                daemon.stopAndWait()
            }
            assertFalse(daemon.isRunning)
            Files.list(temporary.resolve("cache/runs")).use { assertEquals(0L, it.count()) }
        }
    }

    private fun assertCheckout(
        manager: GitWorkspaceManager,
        remote: String,
        base: String,
        head: String,
        before: String,
        after: String,
    ) {
        val workspace = manager.open(remote, base, head)
        workspace.use {
            assertEquals(base, it.baseSha)
            assertEquals(head, it.headSha)
            assertEquals(before, Files.readString(it.baseDirectory.resolve("Example.java")))
            assertEquals(after, Files.readString(it.headDirectory.resolve("Example.java")))
            val change = it.changes().single()
            assertEquals("Example.java", change.basePath)
            assertEquals("Example.java", change.headPath)
            assertEquals(listOf(ChangedLineRange(1, 1)), change.baseLines)
            assertEquals(listOf(ChangedLineRange(1, 1)), change.headLines)
            assertFalse(change.binary)
        }
        assertFalse(Files.exists(workspace.baseDirectory))
        assertFalse(Files.exists(workspace.headDirectory))
    }

    private fun commit(git: Git, source: Path, contents: String): String {
        Files.writeString(source.resolve("Example.java"), contents)
        git.add().addFilepattern(".").call()
        return git.commit().setNoVerify(true).setMessage("remote fixture")
            .setAuthor("Fixture", "fixture@example.invalid")
            .setCommitter("Fixture", "fixture@example.invalid").call().name
    }

    private companion object {
        const val TEST_TIMEOUT_SECONDS = 30L
        const val SOCKET_TIMEOUT_SECONDS = 10
    }
}
