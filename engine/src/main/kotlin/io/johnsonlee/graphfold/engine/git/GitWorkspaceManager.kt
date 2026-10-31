package io.johnsonlee.graphfold.engine.git

import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest
import java.time.Duration
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import org.eclipse.jgit.errors.MissingObjectException
import org.eclipse.jgit.errors.AmbiguousObjectException
import org.eclipse.jgit.errors.IncorrectObjectTypeException
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevWalk

/** JGit owns a shared bare object cache and isolated working trees backed by object alternates. */
class GitWorkspaceManager(
    private val cacheDirectory: Path,
    private val maximumWorkspaceSlots: Int = DEFAULT_MAXIMUM_WORKSPACE_SLOTS,
    private val workspaceLeaseTimeout: Duration = DEFAULT_WORKSPACE_LEASE_TIMEOUT,
) {
    init {
        require(maximumWorkspaceSlots > 0) { "maximumWorkspaceSlots must be positive" }
        require(!workspaceLeaseTimeout.isNegative && !workspaceLeaseTimeout.isZero) {
            "workspace lease timeout must be positive"
        }
    }
    fun open(repository: String, base: String, head: String): GitWorkspace {
        require(repository.isNotBlank() && !repository.startsWith('-')) { "Repository is required" }
        require(COMMIT.matches(base) && COMMIT.matches(head)) { "Base and head must be commit SHAs" }
        return gitOperation {
            val source = localRepository(repository)
            val key = MessageDigest.getInstance("SHA-256").digest(source.toByteArray())
                .joinToString("") { "%02x".format(it) }
            val cache = cacheDirectory.toAbsolutePath().normalize()
            Files.createDirectories(cache.resolve("repositories"))
            Files.createDirectories(cache.resolve("workspaces"))
            val bare = cache.resolve("repositories/$key.git")
            val lock = cache.resolve("repositories/$key.lock")
            val (baseSha, headSha) = locked(lock) {
                initialize(bare)
                JGitRepositories.open(bare).use { cached ->
                    resolve(cached, source, base) to resolve(cached, source, head)
                }
            }
            // Waiting for capacity must not hold the repository lock and serialize other waiters.
            JGitRepositories.open(bare).use { cached ->
                createWorkspace(cached, bare, cache.resolve("workspaces"), key, baseSha, headSha)
            }
        }
    }

    private fun createWorkspace(
        cached: Repository, bare: Path, runs: Path, key: String, base: String, head: String,
    ): GitWorkspace {
        val lease = leaseWorkspace(runs.resolve(key), maximumWorkspaceSlots, workspaceLeaseTimeout)
        return runCatching {
            checkout(cached, base, lease.directory.resolve("base"))
            checkout(cached, head, lease.directory.resolve("head"))
            GitWorkspace(lease.directory.resolve("base"), lease.directory.resolve("head"), base, head, bare) {
                lease.close()
            }
        }.onFailure {
            val interrupted = Thread.interrupted()
            try {
                runCatching { deleteDirectory(lease.directory) }
            } finally {
                lease.close()
                if (interrupted) Thread.currentThread().interrupt()
            }
        }.getOrThrow()
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
        var resolved = resolveCommit(cached, revision)
        if (resolved == null && revision.length == FULL_SHA_LENGTH) {
            // Servers may reject an unadvertised SHA. Only then fall back to branch discovery.
            try {
                fetch(cached, source, revision)
            } catch (_: IOException) {
                checkGitCancellation()
            }
            resolved = resolveCommit(cached, revision)
        }
        if (resolved == null) {
            fetch(cached, source, "+refs/heads/*:refs/remotes/origin/*")
            resolved = resolveCommit(cached, revision)
        }
        return resolved
            ?: throw GitOperationException("Commit is unavailable; provide a full commit SHA reachable from the remote")
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

/** Revision diagnostics contain only validated object identity, never transport credentials. */
internal fun resolveCommit(repository: Repository, revision: String): String? {
    checkGitCancellation()
    return try {
        val id = repository.resolve(revision) ?: return null
        RevWalk(repository).use { it.parseCommit(id).name }
    } catch (_: AmbiguousObjectException) {
        throw GitOperationException("Commit abbreviation is ambiguous; provide the full 40-character commit SHA")
    } catch (_: IncorrectObjectTypeException) {
        throw GitOperationException("Revision identifies a non-commit object; provide a commit SHA")
    } catch (_: MissingObjectException) {
        null
    }
}
