package io.johnsonlee.graphfold.engine.git

import io.johnsonlee.graphfold.engine.DEFAULT_MAXIMUM_WORKSPACE_SLOTS
import io.johnsonlee.graphfold.engine.defaultWorkspaceLeaseTimeout
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.util.concurrent.ConcurrentHashMap

/** Locks live outside checkouts, so failed materialization can safely remove a slot. */
internal class WorkspaceLease(
    val directory: Path,
    private val channel: FileChannel,
    private val lock: FileLock,
    private val releaseReservation: () -> Unit,
) : AutoCloseable {
    override fun close() {
        try {
            lock.release()
        } finally {
            try {
                channel.close()
            } finally {
                releaseReservation()
            }
        }
    }
}

private val reservedSlots = ConcurrentHashMap.newKeySet<Path>()

internal val DEFAULT_WORKSPACE_LEASE_TIMEOUT: Duration = defaultWorkspaceLeaseTimeout()
private const val LEASE_POLL_MILLIS = 50L

internal fun leaseWorkspace(
    directory: Path,
    maximumSlots: Int = DEFAULT_MAXIMUM_WORKSPACE_SLOTS,
    timeout: Duration = DEFAULT_WORKSPACE_LEASE_TIMEOUT,
    affinity: ((Path, () -> Boolean) -> Int)? = null,
): WorkspaceLease {
    require(maximumSlots > 0) { "maximumWorkspaceSlots must be positive" }
    require(!timeout.isNegative && !timeout.isZero) { "workspace lease timeout must be positive" }
    Files.createDirectories(directory)
    val pool = directory.toRealPath()
    val started = System.nanoTime()
    val timeoutNanos = timeout.toNanos()
    while (true) {
        checkGitCancellation()
        val hasTime = { System.nanoTime() - started < timeoutNanos }
        if (affinity != null) {
            preferredLease(pool, maximumSlots, affinity, hasTime)?.let { return it }
        }
        for (index in 0 until maximumSlots) {
            checkGitCancellation()
            val slot = pool.resolve("slot-$index")
            tryLease(slot)?.let { return it }
        }
        val remaining = timeoutNanos - (System.nanoTime() - started)
        if (remaining <= 0) {
            throw WorkspaceLeaseTimeoutException()
        }
        TimeUnit.NANOSECONDS.sleep(minOf(remaining, TimeUnit.MILLISECONDS.toNanos(LEASE_POLL_MILLIS)))
    }
}

/** Keep at most two nonblocking leases, and never inspect the mutable state of a busy checkout. */
private fun preferredLease(
    pool: Path, maximumSlots: Int, affinity: (Path, () -> Boolean) -> Int, hasTime: () -> Boolean,
): WorkspaceLease? {
    val indices = Files.list(pool).use { paths ->
        paths.map { it.fileName.toString() }.filter { it.startsWith("slot-") }
            .map { it.removePrefix("slot-").toIntOrNull() }.filter { it != null && it >= 0 && it < maximumSlots }
            .sorted().toList()
    }
    return PreferredWorkspace().use { selection ->
        for (index in indices) {
            checkGitCancellation()
            tryLease(pool.resolve("slot-$index"))?.let { selection.consider(it, affinity, hasTime) }
            if (selection.exact || (selection.available && !hasTime())) break
        }
        selection.take()
    }
}

/** Owns retained candidates until ownership transfers to the caller; errors close every acquired lease. */
private class PreferredWorkspace : AutoCloseable {
    private var best: WorkspaceLease? = null
    private var bestScore = OTHER_WORKSPACE_AFFINITY
    val exact: Boolean get() = bestScore == 0
    val available: Boolean get() = best != null

    fun consider(candidate: WorkspaceLease, affinity: (Path, () -> Boolean) -> Int, hasTime: () -> Boolean) {
        var retained = false
        try {
            val score = affinity(candidate.directory, hasTime)
            if (best == null || score < bestScore) {
                best?.close()
                best = candidate
                bestScore = score
                retained = true
            }
        } finally {
            if (!retained) candidate.close()
        }
    }

    fun take(): WorkspaceLease? = best.also { best = null }

    override fun close() {
        best?.close()
    }
}

private fun tryLease(slot: Path): WorkspaceLease? {
    if (!reservedSlots.add(slot)) return null
    val channel = runCatching {
        FileChannel.open(slot.resolveSibling("${slot.fileName}.lock"), CREATE, WRITE)
    }.onFailure { reservedSlots.remove(slot) }.getOrThrow()
    val lock = runCatching { channel.tryLock() }.onFailure {
        channel.close()
        reservedSlots.remove(slot)
    }.getOrThrow()
    return if (lock != null) {
        WorkspaceLease(slot, channel, lock) { reservedSlots.remove(slot) }
    } else {
        channel.close()
        reservedSlots.remove(slot)
        null
    }
}
