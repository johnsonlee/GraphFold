package io.johnsonlee.graphfold.engine.git

import java.nio.channels.FileChannel
import java.nio.channels.FileLock
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

internal fun leaseWorkspace(directory: Path): WorkspaceLease {
    Files.createDirectories(directory)
    var index = 0
    while (true) {
        checkGitCancellation()
        val slot = directory.resolve("slot-${index++}")
        if (!reservedSlots.add(slot)) continue
        val channel = runCatching {
            FileChannel.open(directory.resolve("${slot.fileName}.lock"), CREATE, WRITE)
        }.onFailure { reservedSlots.remove(slot) }.getOrThrow()
        val lock = runCatching { channel.tryLock() }.onFailure {
            channel.close()
            reservedSlots.remove(slot)
        }.getOrThrow()
        if (lock != null) {
            return WorkspaceLease(slot, channel, lock) { reservedSlots.remove(slot) }
        }
        channel.close()
        reservedSlots.remove(slot)
    }
}
