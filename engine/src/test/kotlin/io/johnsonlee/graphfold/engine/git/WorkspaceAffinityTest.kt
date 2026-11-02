package io.johnsonlee.graphfold.engine.git

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ResetCommand
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorkspaceAffinityTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `higher exact pair wins while busy preferred and reduced capacity use eligible fallback`() = repository { git, source ->
        val base = commit(git)
        val older = commit(git)
        val head = commit(git)
        val manager = GitWorkspaceManager(temporary.resolve("cache"), maximumWorkspaceSlots = 2)
        val first = manager.open(source.toString(), base, older)
        val firstPath = first.baseDirectory.parent
        val secondPath = manager.open(source.toString(), base, head).use { it.baseDirectory.parent }
        first.close()
        manager.open(source.toString(), base, head).use { preferred ->
            assertEquals(secondPath, preferred.baseDirectory.parent)
            manager.open(source.toString(), base, head).use { fallback ->
                assertEquals(firstPath, fallback.baseDirectory.parent)
            }
        }
        GitWorkspaceManager(temporary.resolve("cache"), maximumWorkspaceSlots = 1)
            .open(source.toString(), base, head).use { assertEquals(firstPath, it.baseDirectory.parent) }
    }

    @Test
    fun `same base with nearest head wins over distant history and another base with exact head`() = repository { git, source ->
        val commits = List(6) { commit(git) }
        val manager = GitWorkspaceManager(temporary.resolve("cache"), maximumWorkspaceSlots = 3)
        val differentBase = manager.open(source.toString(), commits[1], commits[5])
        val distantHead = manager.open(source.toString(), commits[0], commits[1])
        val nearestPath = manager.open(source.toString(), commits[0], commits[4]).use { it.baseDirectory.parent }
        differentBase.close()
        distantHead.close()
        manager.open(source.toString(), commits[0], commits[5]).use { assertEquals(nearestPath, it.baseDirectory.parent) }
    }

    @Test
    fun `sibling branches score through their observed common ancestor`() = repository { git, _ ->
        val base = commit(git)
        commit(git)
        val requested = commit(git)
        git.reset().setMode(ResetCommand.ResetType.HARD).setRef(base).call()
        val sibling = commit(git)
        val slot = slot(git, "slot", base, sibling)
        assertEquals(4, WorkspaceAffinity(git.repository, base, requested).score(slot) { true })
    }

    @Test
    fun `missing malformed and unavailable checkout hints fall back conservatively`() = repository { git, _ ->
        val base = commit(git)
        val head = commit(git)
        val affinity = WorkspaceAffinity(git.repository, base, head)
        assertEquals(OTHER_WORKSPACE_AFFINITY, affinity.score(temporary.resolve("absent")) { true })
        val slot = slot(git, "slot", base, base)
        Files.delete(slot.resolve("head/.git/HEAD"))
        assertEquals(UNKNOWN_SAME_BASE_AFFINITY, affinity.score(slot) { true })
        slot.resolve("head/.git/HEAD").writeText("not-a-valid-object\n")
        assertEquals(UNKNOWN_SAME_BASE_AFFINITY, affinity.score(slot) { true })
        slot.resolve("head/.git/HEAD").writeText("1111111111111111111111111111111111111111\n")
        assertEquals(UNKNOWN_SAME_BASE_AFFINITY, affinity.score(slot) { true })
        slot.resolve("head/.git/config").writeText("[broken\n")
        assertEquals(UNKNOWN_SAME_BASE_AFFINITY, affinity.score(slot) { true })
    }

    @Test
    fun `history bound and expired scoring budget produce deterministic unknown preference`() = repository { git, _ ->
        val base = commit(git)
        val old = commit(git)
        repeat(AFFINITY_HISTORY_LIMIT + 1) { commit(git) }
        val head = commit(git)
        val slot = slot(git, "slot", base, old)
        assertEquals(UNKNOWN_SAME_BASE_AFFINITY, WorkspaceAffinity(git.repository, base, head).score(slot) { true })
        assertEquals(UNKNOWN_SAME_BASE_AFFINITY, WorkspaceAffinity(git.repository, base, head).score(slot) { false })
        val missing = "2222222222222222222222222222222222222222"
        assertEquals(UNKNOWN_SAME_BASE_AFFINITY, WorkspaceAffinity(git.repository, base, missing).score(slot) { true })
    }

    @Test
    fun `scoring failure and cancellation release both current and retained candidates`() {
        val pool = Files.createDirectory(temporary.resolve("pool"))
        Files.createDirectory(pool.resolve("slot-0"))
        Files.createDirectory(pool.resolve("slot-1"))
        assertFailsWith<IllegalStateException> {
            leaseWorkspace(pool, 2, affinity = { path, _ ->
                if (path.fileName.toString() == "slot-1") error("scoring failed")
                10
            })
        }
        assertBothAvailable(pool)
        assertFailsWith<InterruptedException> {
            leaseWorkspace(pool, 2, affinity = { path, _ ->
                if (path.fileName.toString() == "slot-1") Thread.currentThread().interrupt()
                checkGitCancellation()
                10
            })
        }
        assertTrue(Thread.interrupted())
        assertBothAvailable(pool)
    }

    @Test
    fun `scoring deadline returns available slot and empty huge pool allocates lazily`() {
        val pool = Files.createDirectory(temporary.resolve("pool"))
        Files.createDirectory(pool.resolve("slot-0"))
        Files.createDirectory(pool.resolve("slot-1"))
        leaseWorkspace(pool, 2, Duration.ofNanos(1), affinity = { _, hasTime ->
            assertFalse(hasTime())
            OTHER_WORKSPACE_AFFINITY
        }).use { assertEquals("slot-0", it.directory.fileName.toString()) }
        val empty = temporary.resolve("empty")
        leaseWorkspace(empty, Int.MAX_VALUE, affinity = { _, _ -> error("No existing slot") }).use {
            assertEquals("slot-0", it.directory.fileName.toString())
            assertFalse(Files.exists(empty.resolve("slot-1.lock")))
        }
    }

    @Test
    fun `expired ranking budget skips busy retained slot before considering new slots`() {
        val pool = Files.createDirectory(temporary.resolve("pool"))
        val empty = leaseWorkspace(pool, 1)
        val busy = leaseWorkspace(pool, 2)
        Files.createDirectory(busy.directory)
        Files.createDirectory(pool.resolve("slot-2"))
        empty.close()
        busy.use {
            leaseWorkspace(pool, 3, Duration.ofNanos(1), affinity = { _, hasTime ->
                assertFalse(hasTime())
                OTHER_WORKSPACE_AFFINITY
            }).use { selected ->
                assertEquals("slot-2", selected.directory.fileName.toString())
            }
            assertFalse(Files.exists(pool.resolve("slot-0")))
        }
    }

    @Test
    fun `unknown affinity ties follow numeric slot order and ignore unrelated entries`() {
        val pool = Files.createDirectory(temporary.resolve("pool"))
        for (name in listOf("slot-0", "slot-1", "slot-2", "slot-9", "slot--1", "slot-999999999999999", "other")) {
            Files.createDirectory(pool.resolve(name))
        }
        leaseWorkspace(pool, 3, affinity = { _, _ -> OTHER_WORKSPACE_AFFINITY }).use {
            assertEquals("slot-0", it.directory.fileName.toString())
        }
        leaseWorkspace(pool, 1).use {
            leaseWorkspace(pool, 3, affinity = { _, _ -> UNKNOWN_SAME_BASE_AFFINITY }).use { next ->
                assertEquals("slot-1", next.directory.fileName.toString())
            }
        }
    }

    private fun assertBothAvailable(pool: Path) {
        leaseWorkspace(pool, 1).use {
            leaseWorkspace(pool, 2).use { second -> assertEquals("slot-1", second.directory.fileName.toString()) }
        }
    }

    private fun slot(git: Git, name: String, base: String, head: String): Path = temporary.resolve(name).apply {
        checkout(git.repository, base, resolve("base"))
        checkout(git.repository, head, resolve("head"))
    }

    private fun repository(action: (Git, Path) -> Unit) {
        val source = temporary.resolve("source")
        Git.init().setDirectory(source.toFile()).call().use { action(it, source) }
    }

    private var sequence = 0
    private fun commit(git: Git): String = git.commit().setAllowEmpty(true).setNoVerify(true).setMessage("commit-${sequence++}")
        .setAuthor("Fixture", "fixture@example.invalid").setCommitter("Fixture", "fixture@example.invalid").call().name
}
