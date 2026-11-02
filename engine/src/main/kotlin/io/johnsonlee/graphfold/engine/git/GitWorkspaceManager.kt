package io.johnsonlee.graphfold.engine.git

import io.johnsonlee.graphfold.engine.DEFAULT_MAXIMUM_WORKSPACE_SLOTS
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

/**
 * Manages a shared JGit object cache and persistent, isolated working trees backed by object alternates.
 * Slots prefer the same base revision and nearby head revisions to retain useful incremental build state.
 *
 * @param cacheDirectory Root for repository object caches, workspace slots, and their locks.
 * @param maximumWorkspaceSlots Positive maximum number of simultaneously leased slots per repository.
 * @param workspaceLeaseTimeout Positive time budget for acquiring a free slot; it does not limit lease lifetime.
 * @throws IllegalArgumentException if the slot count or lease timeout is not positive.
 */
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
    /**
     * Resolves both commits and exclusively leases their checkouts; the caller must close the returned workspace.
     * Missing objects are fetched into the shared cache before checkout. The caller's repository is not modified.
     *
     * @param repository Local repository path or remote URL understood by JGit.
     * @param base Hexadecimal base commit SHA, full length or an unambiguous abbreviation of at least seven characters.
     * @param head Hexadecimal head commit SHA, full length or an unambiguous abbreviation of at least seven characters.
     * @throws IllegalArgumentException if the repository is blank or the revisions are not hexadecimal SHA strings.
     * @throws WorkspaceLeaseTimeoutException if no slot becomes available within the configured wait budget.
     * @throws GitOperationException if fetching, resolving, or materializing either revision fails.
     * @throws InterruptedException if the calling thread is interrupted.
     */
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
        val affinity = WorkspaceAffinity(cached, base, head)
        val lease = leaseWorkspace(runs.resolve(key), maximumWorkspaceSlots, workspaceLeaseTimeout, affinity::score)
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
