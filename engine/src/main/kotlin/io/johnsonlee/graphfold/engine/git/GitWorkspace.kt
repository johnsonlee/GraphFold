package io.johnsonlee.graphfold.engine.git

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.LinkOption.NOFOLLOW_LINKS

/**
 * Exclusively leases two persistent private checkouts until [close] releases their slot.
 * Closing retains source trees and incremental build outputs for later reuse.
 *
 * @property baseDirectory Working directory materialized at [baseSha].
 * @property headDirectory Working directory materialized at [headSha].
 * @property baseSha Resolved full commit SHA of the base revision.
 * @property headSha Resolved full commit SHA of the head revision.
 */
class GitWorkspace internal constructor(
    val baseDirectory: Path,
    val headDirectory: Path,
    val baseSha: String,
    val headSha: String,
    private val repository: Path,
    private val cleanup: () -> Unit,
) : AutoCloseable {
    private var closed = false

    /**
     * Reads tree changes between the leased commits, independently of working-directory modifications.
     *
     * @throws IllegalStateException if this workspace has already been closed.
     * @throws GitOperationException if the tree comparison cannot complete.
     * @throws InterruptedException if the calling thread is interrupted.
     */
    @Synchronized
    @JvmSynthetic
    internal fun changes(): List<ChangedFile> {
        check(!closed) { "Workspace is closed" }
        return diff(repository, baseSha, headSha)
    }

    /** Releases the exclusive workspace lease; subsequent successful closes have no effect. */
    @Synchronized
    override fun close() {
        if (!closed) {
            cleanup()
            closed = true
        }
    }
}

internal fun deleteDirectory(path: Path) {
    if (Files.exists(path, NOFOLLOW_LINKS)) {
        Files.walk(path).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }
}
