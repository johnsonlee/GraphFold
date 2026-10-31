package io.johnsonlee.graphfold.engine.git

import io.johnsonlee.graphfold.engine.EngineConfiguration
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorkspaceCapacityTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `capacity waits and reuses a released slot without creating another checkout`() {
        val first = leaseWorkspace(temporary, 1)
        val started = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val waiting = executor.submit<WorkspaceLease> {
                started.countDown()
                leaseWorkspace(temporary, 1, Duration.ofSeconds(WAIT_SECONDS))
            }
            assertTrue(started.await(WAIT_SECONDS, TimeUnit.SECONDS))
            assertFalse(waiting.isDone)
            first.close()
            waiting.get(WAIT_SECONDS, TimeUnit.SECONDS).use { assertEquals(first.directory, it.directory) }
            assertFalse(Files.exists(temporary.resolve("slot-1.lock")))
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `capacity timeout is actionable and does not leak a reservation`() {
        leaseWorkspace(temporary, 1).use {
            val failure = assertFailsWith<GitOperationException> {
                leaseWorkspace(temporary, 1, Duration.ofMillis(1))
            }
            assertTrue(failure.message.orEmpty().contains("Workspace capacity exhausted"))
            assertFalse(Files.exists(temporary.resolve("slot-1.lock")))
        }
        leaseWorkspace(temporary, 1).close()
    }

    @Test
    fun `waiting is cancellable without leaking the busy slot`() {
        leaseWorkspace(temporary, 1).use {
            val started = CountDownLatch(1)
            var cancelled = false
            val worker = Thread {
                started.countDown()
                try {
                    leaseWorkspace(temporary, 1).close()
                } catch (_: InterruptedException) {
                    cancelled = true
                }
            }
            worker.start()
            assertTrue(started.await(WAIT_SECONDS, TimeUnit.SECONDS))
            worker.interrupt()
            worker.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS))
            assertFalse(worker.isAlive)
            assertTrue(cancelled)
        }
        leaseWorkspace(temporary, 1).close()
    }

    @Test
    fun `lease pool rejects invalid configuration and normalizes path aliases`() {
        for (duration in listOf(Duration.ZERO, Duration.ofMillis(-1))) {
            assertFailsWith<IllegalArgumentException> { leaseWorkspace(temporary, 1, duration) }
            assertFailsWith<IllegalArgumentException> { GitWorkspaceManager(temporary, 1, duration) }
            assertFailsWith<IllegalArgumentException> { EngineConfiguration(temporary, workspaceLeaseTimeout = duration) }
        }
        assertFailsWith<IllegalArgumentException> { leaseWorkspace(temporary, 0) }
        assertFailsWith<IllegalArgumentException> { GitWorkspaceManager(temporary, 0) }
        assertFailsWith<IllegalArgumentException> { EngineConfiguration(temporary, maximumWorkspaceSlots = 0) }
        val pool = Files.createDirectory(temporary.resolve("pool"))
        val alias = Files.createSymbolicLink(temporary.resolve("alias"), pool)
        leaseWorkspace(pool, 1).use {
            assertFailsWith<GitOperationException> { leaseWorkspace(alias, 1, Duration.ofMillis(1)) }
        }
        leaseWorkspace(alias, 1).use { assertEquals(pool.toRealPath().resolve("slot-0"), it.directory) }
    }

    private companion object { const val WAIT_SECONDS = 5L }
}
