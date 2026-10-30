package io.johnsonlee.graphfold.engine.git

import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import org.eclipse.jgit.errors.MissingObjectException
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevWalk

/** JGit owns a shared bare object cache and isolated working trees backed by object alternates. */
class GitWorkspaceManager(private val cacheDirectory: Path) {
    fun open(repository: String, base: String, head: String): GitWorkspace {
        require(repository.isNotBlank() && !repository.startsWith('-')) { "Repository is required" }
        require(COMMIT.matches(base) && COMMIT.matches(head)) { "Base and head must be commit SHAs" }
        return gitOperation {
            val source = localRepository(repository)
            val key = MessageDigest.getInstance("SHA-256").digest(source.toByteArray())
                .joinToString("") { "%02x".format(it) }
            val cache = cacheDirectory.toAbsolutePath().normalize()
            Files.createDirectories(cache.resolve("repositories"))
            Files.createDirectories(cache.resolve("runs"))
            val bare = cache.resolve("repositories/$key.git")
            val lock = cache.resolve("repositories/$key.lock")
            locked(lock) {
                initialize(bare)
                JGitRepositories.open(bare).use { cached ->
                    val baseSha = resolve(cached, source, base)
                    val headSha = resolve(cached, source, head)
                    createWorkspace(cached, bare, cache.resolve("runs"), key, baseSha, headSha)
                }
            }
        }
    }

    private fun createWorkspace(
        cached: Repository, bare: Path, runs: Path, key: String, base: String, head: String,
    ): GitWorkspace {
        val run = Files.createTempDirectory(runs, "$key-")
        return runCatching {
            checkout(cached, base, run.resolve("base"))
            checkout(cached, head, run.resolve("head"))
            GitWorkspace(run.resolve("base"), run.resolve("head"), base, head, bare) {
                cleanup(run)
            }
        }.onFailure {
            val interrupted = Thread.interrupted()
            try {
                runCatching { deleteDirectory(run) }
            } finally {
                if (interrupted) Thread.currentThread().interrupt()
            }
        }.getOrThrow()
    }

    private fun cleanup(run: Path) {
        val interrupted = Thread.interrupted()
        try {
            gitOperation { deleteDirectory(run) }
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    private fun initialize(bare: Path) {
        if (!Files.exists(bare.resolve("HEAD"))) {
            runCatching {
                JGitRepositories.open(bare).use { it.create(true) }
            }.onFailure {
                runCatching { deleteDirectory(bare) }
            }.getOrThrow()
        }
    }

    private fun resolve(cached: Repository, source: String, revision: String): String {
        var resolved = resolveExisting(cached, revision)
        if (resolved == null) {
            fetch(cached, source, "+refs/heads/*:refs/remotes/origin/*")
            resolved = resolveExisting(cached, revision)
        }
        if (resolved == null && revision.length == FULL_SHA_LENGTH) {
            fetch(cached, source, revision)
            resolved = resolveExisting(cached, revision)
        }
        return resolved ?: throw GitOperationException("Commit is unavailable")
    }

    private fun resolveExisting(repository: Repository, revision: String): String? {
        checkGitCancellation()
        val id = repository.resolve(revision) ?: return null
        return try {
            RevWalk(repository).use { it.parseCommit(id).name }
        } catch (_: MissingObjectException) {
            null
        }
    }

    private fun localRepository(repository: String): String {
        val candidate = Path.of(repository)
        return if (Files.exists(candidate)) candidate.toRealPath().toString() else repository
    }

    private fun <T> locked(path: Path, action: () -> T): T {
        val lock = LOCKS.computeIfAbsent(path) { ReentrantLock() }
        lock.lockInterruptibly()
        try {
            return FileChannel.open(path, CREATE, WRITE).use { channel -> channel.lock().use { action() } }
        } finally {
            lock.unlock()
        }
    }

    private companion object {
        val COMMIT = Regex("[0-9a-fA-F]{7,40}")
        val LOCKS = ConcurrentHashMap<Path, ReentrantLock>()
        const val FULL_SHA_LENGTH = 40
    }
}

/** No transport exception, remote URL, or credential-bearing cause is exposed to callers. */
internal fun <T> gitOperation(action: () -> T): T = runCatching {
    checkGitCancellation()
    action()
}.getOrElse { failure ->
    throw when {
        failure !is Exception -> failure
        failure is InterruptedException || Thread.currentThread().isInterrupted -> {
            Thread.currentThread().interrupt()
            InterruptedException("Git operation interrupted")
        }
        failure is GitOperationException -> failure
        else -> GitOperationException("Git operation failed")
    }
}

internal fun checkGitCancellation() {
    if (Thread.currentThread().isInterrupted) throw InterruptedException("Git operation interrupted")
}
