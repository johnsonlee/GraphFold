package io.johnsonlee.graphfold.engine.git

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentLinkedQueue
import org.eclipse.jgit.api.Git
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GitHttpTransportTest {
    @TempDir
    lateinit var temporary: Path

    @Test
    @Timeout(TEST_TIMEOUT_SECONDS)
    fun `fetches and refreshes exact commits over unauthenticated dumb HTTP`() {
        val source = temporary.resolve("source")
        Git.init().setDirectory(source.toFile()).call().use { git ->
            git.repository.config.apply {
                setString("core", null, "hooksPath", Files.createDirectory(temporary.resolve("empty-hooks")).toString())
                save()
            }
            val base = commit(git, source, "before\n")
            val head = commit(git, source, "after\n")
            val requests = ConcurrentLinkedQueue<String>()
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext(REPOSITORY_PATH) { exchange ->
                    serve(exchange, git.repository.directory.toPath(), requests)
                }
                start()
            }
            try {
                val remote = "http://127.0.0.1:${server.address.port}$REPOSITORY_PATH"
                val manager = GitWorkspaceManager(temporary.resolve("cache"))
                assertCheckout(manager, remote, base, head, "before\n", "after\n")
                val next = commit(git, source, "updated remote\n")
                assertCheckout(manager, remote, head, next, "after\n", "updated remote\n")
                assertTrue(requests.count { it == "info/refs" } >= MINIMUM_FETCHES)
                assertTrue(requests.any { it.startsWith("objects/") && it != "objects/info/packs" })
                assertEquals(next, git.repository.resolve("HEAD").name)
            } finally {
                server.stop(0)
            }
            Files.list(temporary.resolve("cache/workspaces")).use { assertEquals(1L, it.count()) }
        }
    }

    private fun serve(exchange: HttpExchange, repository: Path, requests: ConcurrentLinkedQueue<String>) {
        exchange.use {
            val relative = exchange.requestURI.path.removePrefix(REPOSITORY_PATH)
            requests.add(relative)
            val file = repository.resolve(relative).normalize()
            if (file.startsWith(repository) && Files.isRegularFile(file)) {
                val bytes = Files.readAllBytes(file)
                // A non-smart content type makes JGit use loose-object HTTP discovery and fetching.
                exchange.responseHeaders.set("Content-Type", "text/plain")
                exchange.sendResponseHeaders(HTTP_OK, bytes.size.toLong())
                exchange.responseBody.write(bytes)
            } else {
                exchange.sendResponseHeaders(HTTP_NOT_FOUND, -1)
            }
        }
    }

    private fun assertCheckout(
        manager: GitWorkspaceManager, remote: String, base: String, head: String, before: String, after: String,
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
        assertTrue(Files.exists(workspace.baseDirectory))
        assertTrue(Files.exists(workspace.headDirectory))
    }

    private fun commit(git: Git, source: Path, contents: String): String {
        Files.writeString(source.resolve("Example.java"), contents)
        git.add().addFilepattern(".").call()
        val revision = git.commit().setNoVerify(true).setMessage("HTTP fixture")
            .setAuthor("Fixture", "fixture@example.invalid")
            .setCommitter("Fixture", "fixture@example.invalid").call().name
        val info = Files.createDirectories(git.repository.directory.toPath().resolve("info"))
        // Equivalent to update-server-info for this single-branch, loose-object fixture.
        Files.writeString(info.resolve("refs"), "$revision\t${git.repository.fullBranch}\n")
        return revision
    }

    private companion object {
        const val REPOSITORY_PATH = "/fixture.git/"
        const val TEST_TIMEOUT_SECONDS = 30L
        const val MINIMUM_FETCHES = 2
        const val HTTP_OK = 200
        const val HTTP_NOT_FOUND = 404
    }
}
