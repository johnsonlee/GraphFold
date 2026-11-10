package io.johnsonlee.graphfold.engine.git

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class FetchFallbackTest {
    @TempDir lateinit var temporary: Path

    @Test fun `rejected exact SHA transport retries branch discovery and remains a failure`() {
        val requests = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/repository.git") { exchange ->
            requests.incrementAndGet()
            exchange.sendResponseHeaders(503, -1)
            exchange.close()
        }
        server.start()
        try {
            val failure = assertFailsWith<GitOperationException> {
                val remote = "http://127.0.0.1:${server.address.port}/repository.git"
                GitWorkspaceManager(temporary).open(remote, "0".repeat(40), "1".repeat(40))
            }
            assertEquals(2, requests.get(), "Exact SHA failure must be followed by one branch discovery attempt")
            assertFalse(failure.message.contains("127.0.0.1"), "Transport details must remain sanitized")
        } finally {
            server.stop(0)
        }
    }
}
