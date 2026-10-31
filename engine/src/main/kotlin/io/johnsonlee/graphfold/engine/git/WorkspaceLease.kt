package io.johnsonlee.graphfold.engine.git

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

internal const val DEFAULT_MAXIMUM_WORKSPACE_SLOTS = 2
internal val DEFAULT_WORKSPACE_LEASE_TIMEOUT: Duration = Duration.ofMinutes(5)
private const val LEASE_POLL_MILLIS = 50L

internal fun leaseWorkspace(
    directory: Path,
    maximumSlots: Int = DEFAULT_MAXIMUM_WORKSPACE_SLOTS,
    timeout: Duration = DEFAULT_WORKSPACE_LEASE_TIMEOUT,
): WorkspaceLease {
    require(maximumSlots > 0) { "maximumWorkspaceSlots must be positive" }
    require(!timeout.isNegative && !timeout.isZero) { "workspace lease timeout must be positive" }
    Files.createDirectories(directory)
    val pool = directory.toRealPath()
    val started = System.nanoTime()
    val timeoutNanos = timeout.toNanos()
    while (true) {
        checkGitCancellation()
        for (index in 0 until maximumSlots) {
            val slot = pool.resolve("slot-$index")
            tryLease(slot)?.let { return it }
        }
        val remaining = timeoutNanos - (System.nanoTime() - started)
        if (remaining <= 0) {
            throw GitOperationException("Workspace capacity exhausted; timed out waiting for a reusable checkout slot")
        }
        TimeUnit.NANOSECONDS.sleep(minOf(remaining, TimeUnit.MILLISECONDS.toNanos(LEASE_POLL_MILLIS)))
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
