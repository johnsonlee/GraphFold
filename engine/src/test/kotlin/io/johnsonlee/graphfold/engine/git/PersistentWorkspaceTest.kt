package io.johnsonlee.graphfold.engine.git

import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.BasicFileAttributeView
import org.eclipse.jgit.api.Git
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PersistentWorkspaceTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `repeated revisions retain build state stable paths and unchanged source timestamps`() = repository { git, source ->
        Files.writeString(source.resolve("Example.java"), "class Example {}")
        val base = commit(git)
        val manager = GitWorkspaceManager(temporary.resolve("cache"))
        val first = manager.open(source.toString(), base, base)
        val timestamp = FileTime.fromMillis(SOURCE_TIMESTAMP)
        Files.setLastModifiedTime(first.headDirectory.resolve("Example.java"), timestamp)
        val state = Files.createDirectories(first.headDirectory.resolve("build/classes")).resolve("state")
        Files.writeString(state, "cached compilation")
        val gradleState = Files.createDirectories(first.headDirectory.resolve(".gradle/graphfold")).resolve("state")
        Files.writeString(gradleState, "compiler provenance")
        first.close()
        Files.writeString(source.resolve("Added.java"), "class Added {}")
        val next = commit(git)
        manager.open(source.toString(), base, next).use {
            assertEquals(first.baseDirectory, it.baseDirectory)
            assertEquals(first.headDirectory, it.headDirectory)
            assertEquals(timestamp, Files.getLastModifiedTime(it.headDirectory.resolve("Example.java")))
            assertEquals("cached compilation", Files.readString(state))
            assertEquals("compiler provenance", Files.readString(gradleState))
            assertTrue(Files.exists(it.headDirectory.resolve("Added.java")))
            assertFalse(Files.exists(it.baseDirectory.resolve("Added.java")))
        }
    }

    @Test
    fun `reuse restores modified tracked sources and removes stale tracked and untracked files`() = repository { git, source ->
        Files.writeString(source.resolve("Example.java"), "original")
        Files.writeString(source.resolve("Removed.java"), "removed")
        Files.writeString(source.resolve(".gitignore"), "cached/\n")
        val base = commit(git)
        val manager = GitWorkspaceManager(temporary.resolve("cache"))
        manager.open(source.toString(), base, base).use {
            Files.writeString(it.headDirectory.resolve("Example.java"), "build mutated source")
            Files.writeString(it.headDirectory.resolve("Untracked.java"), "stale source")
            Files.writeString(Files.createDirectories(it.headDirectory.resolve("cached")).resolve("stale"), "old ignored")
        }
        Files.delete(source.resolve("Removed.java"))
        Files.writeString(source.resolve(".gitignore"), "")
        val next = commit(git)
        manager.open(source.toString(), base, next).use {
            assertEquals("original", Files.readString(it.headDirectory.resolve("Example.java")))
            assertFalse(Files.exists(it.headDirectory.resolve("Removed.java")))
            assertFalse(Files.exists(it.headDirectory.resolve("Untracked.java")))
            assertFalse(Files.exists(it.headDirectory.resolve("cached/stale")))
        }
    }

    @Test
    fun `tracked parent replaced by a symlink cannot redirect source restoration outside the slot`() = repository { git, source ->
        Files.writeString(Files.createDirectories(source.resolve("nested")).resolve("tracked"), "tracked")
        val base = commit(git)
        val manager = GitWorkspaceManager(temporary.resolve("cache"))
        val external = Files.createDirectories(temporary.resolve("external"))
        Files.writeString(external.resolve("tracked"), "must survive")
        manager.open(source.toString(), base, base).use {
            deleteDirectory(it.headDirectory.resolve("nested"))
            Files.createSymbolicLink(it.headDirectory.resolve("nested"), external)
        }
        Files.delete(source.resolve("nested/tracked"))
        val next = commit(git)
        manager.open(source.toString(), base, next).use {
            assertEquals("must survive", Files.readString(external.resolve("tracked")))
            assertFalse(Files.exists(it.headDirectory.resolve("nested/tracked")))
        }
    }

    @Test
    fun `reuse safely replaces symlinks directories and executable modes`() = repository { git, source ->
        Files.writeString(source.resolve("target"), "content")
        Files.createSymbolicLink(source.resolve("link"), Path.of("target"))
        val base = commit(git)
        val manager = GitWorkspaceManager(temporary.resolve("cache"))
        manager.open(source.toString(), base, base).close()
        Files.delete(source.resolve("link"))
        Files.createDirectory(source.resolve("link"))
        Files.writeString(source.resolve("link/nested"), "nested")
        assertTrue(source.resolve("target").toFile().setExecutable(true))
        val next = commit(git)
        manager.open(source.toString(), next, next).use {
            assertFalse(Files.isSymbolicLink(it.headDirectory.resolve("link")))
            assertEquals("nested", Files.readString(it.headDirectory.resolve("link/nested")))
            assertTrue(Files.isExecutable(it.headDirectory.resolve("target")))
        }
        manager.open(source.toString(), base, base).use {
            assertTrue(Files.isSymbolicLink(it.headDirectory.resolve("link")))
            assertFalse(Files.isExecutable(it.headDirectory.resolve("target")))
        }
    }

    @Test
    fun `unchanged symbolic links retain timestamps while retargeted links are restored`() = repository { git, source ->
        Files.writeString(source.resolve("target"), "content")
        Files.createSymbolicLink(source.resolve("link"), Path.of("target"))
        val revision = commit(git)
        val manager = GitWorkspaceManager(temporary.resolve("cache"))
        val first = manager.open(source.toString(), revision, revision)
        val timestamp = FileTime.fromMillis(SOURCE_TIMESTAMP)
        Files.getFileAttributeView(first.baseDirectory.resolve("link"), BasicFileAttributeView::class.java, NOFOLLOW_LINKS)
            .setTimes(timestamp, null, null)
        Files.delete(first.headDirectory.resolve("link"))
        Files.createSymbolicLink(first.headDirectory.resolve("link"), Path.of("missing-target"))
        first.close()
        manager.open(source.toString(), revision, revision).use {
            assertEquals(first.baseDirectory, it.baseDirectory)
            assertEquals(timestamp, Files.getLastModifiedTime(it.baseDirectory.resolve("link"), NOFOLLOW_LINKS))
            assertEquals(Path.of("target"), Files.readSymbolicLink(it.baseDirectory.resolve("link")))
            assertEquals(Path.of("target"), Files.readSymbolicLink(it.headDirectory.resolve("link")))
            assertEquals("content", Files.readString(it.headDirectory.resolve("link")))
        }
    }

    @Test
    fun `a damaged lock path does not leak its JVM reservation`() {
        val pool = Files.createDirectories(temporary.resolve("slots"))
        val invalidLock = Files.createDirectory(pool.resolve("slot-0.lock"))
        assertFailsWith<IOException> { leaseWorkspace(pool) }
        Files.delete(invalidLock)
        leaseWorkspace(pool).use { assertEquals("slot-0", it.directory.fileName.toString()) }
    }

    @Test
    fun `an overlapping JVM lock failure releases its channel and reservation`() {
        val pool = Files.createDirectories(temporary.resolve("slots"))
        FileChannel.open(pool.resolve("slot-0.lock"), CREATE, WRITE).use { channel ->
            channel.lock().use {
                assertFailsWith<OverlappingFileLockException> { leaseWorkspace(pool) }
            }
        }
        leaseWorkspace(pool).use { assertEquals("slot-0", it.directory.fileName.toString()) }
    }

    @Test
    fun `a busy file lease is skipped and becomes reusable after release`() {
        val pool = temporary.resolve("slots")
        Files.createDirectories(pool)
        FileChannel.open(pool.resolve("slot-0.lock"), CREATE, WRITE).use { channel ->
            // Exercise a real external-process lock: same-JVM file locks throw rather than returning null.
            val javaExecutable = Path.of(System.getProperty("java.home"), "bin/java").toString()
            val classpath = requireNotNull(System.getProperty("graphfold.test.runtimeClasspath"))
            val ready = temporary.resolve("ready")
            val stop = temporary.resolve("stop")
            val process = ProcessBuilder(javaExecutable, "-cp", classpath, WorkspaceLockProbe::class.java.name,
                pool.resolve("slot-0.lock").toString(), ready.toString(), stop.toString()).start()
            try {
                val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(PROBE_TIMEOUT_SECONDS)
                while (!Files.exists(ready) && System.nanoTime() < deadline) Thread.sleep(PROBE_POLL_MILLIS)
                assertTrue(Files.exists(ready))
                leaseWorkspace(pool).use { assertEquals("slot-1", it.directory.fileName.toString()) }
            } finally {
                Files.writeString(stop, "stop")
                val finished = process.waitFor(PROBE_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
                process.destroyForcibly()
                assertTrue(finished)
            }
            assertTrue(channel.isOpen)
        }
        leaseWorkspace(pool).use { assertEquals("slot-0", it.directory.fileName.toString()) }
    }

    @Test
    fun `simultaneous leases remain isolated and close can occur on another thread`() {
        val pool = temporary.resolve("slots")
        val first = leaseWorkspace(pool)
        leaseWorkspace(pool).use { second ->
            assertNotEquals(first.directory, second.directory)
            Thread { first.close() }.apply {
                start()
                join()
            }
            leaseWorkspace(pool).use { assertEquals(first.directory, it.directory) }
        }
    }

    private companion object {
        const val SOURCE_TIMESTAMP = 1_000L
    }

    private fun repository(action: (Git, Path) -> Unit) {
        val source = temporary.resolve("source")
        Git.init().setDirectory(source.toFile()).call().use { action(it, source) }
    }

    private fun commit(git: Git): String {
        git.add().addFilepattern(".").call()
        git.add().addFilepattern(".").setUpdate(true).call()
        return git.commit().setNoVerify(true).setMessage("fixture")
            .setAuthor("Fixture", "fixture@example.invalid").setCommitter("Fixture", "fixture@example.invalid").call().name
    }
}

object WorkspaceLockProbe {
    @JvmStatic
    fun main(arguments: Array<String>) {
        FileChannel.open(Path.of(arguments[0]), CREATE, WRITE).use { channel ->
            channel.lock().use {
                Files.writeString(Path.of(arguments[1]), "ready")
                while (!Files.exists(Path.of(arguments[2]))) Thread.sleep(PROBE_POLL_MILLIS)
            }
        }
    }
}

private const val PROBE_TIMEOUT_SECONDS = 10L
private const val PROBE_POLL_MILLIS = 10L
