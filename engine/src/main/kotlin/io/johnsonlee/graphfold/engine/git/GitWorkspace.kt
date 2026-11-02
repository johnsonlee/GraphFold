package io.johnsonlee.graphfold.engine.git

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.LinkOption.NOFOLLOW_LINKS

/**
 * A one-based inclusive changed line range. Empty sides of a hunk have no range.
 *
 * @property start First changed line, greater than zero.
 * @property end Last changed line, at or after [start].
 * @throws IllegalArgumentException if the range is empty, reversed, or starts before line one.
 */
data class ChangedLineRange(val start: Int, val end: Int) {
    init {
        require(start > 0 && end >= start) { "Invalid changed line range" }
    }
}

/**
 * One edit in Git's unified-diff coordinate system, without surrounding context.
 *
 * @property baseStart One-based first base line; for an empty side, the number of lines before the insertion.
 * @property baseCount Number of removed or replaced base lines, zero for a pure insertion.
 * @property headStart One-based first head line; for an empty side, the number of lines before the deletion.
 * @property headCount Number of added or replacement head lines, zero for a pure deletion.
 */
data class ChangedHunk(val baseStart: Int, val baseCount: Int, val headStart: Int, val headCount: Int)

/** Repository tree operation reported by JGit, including detected renames and copies. */
enum class GitChangeType {
    /** A path exists only in the head tree. */
    ADD,
    /** A path exists only in the base tree. */
    DELETE,
    /** An existing path has different content or mode within the same file type. */
    MODIFY,
    /** A path changes Git file type, such as a regular file becoming a symbolic link. */
    TYPE_CHANGE,
    /** Similarity detection associates a removed path with a new path. */
    RENAME,
    /** Similarity detection associates a new path with a retained source path. */
    COPY,
}

/**
 * A changed repository path pair; line ranges contain changed lines, never context.
 *
 * @property basePath Repository-relative old path, or null for an added file.
 * @property headPath Repository-relative new path, or null for a deleted file.
 * @property baseLines Inclusive changed line ranges in the base revision.
 * @property headLines Inclusive changed line ranges in the head revision.
 * @property binary Whether JGit reports a binary patch, for which text ranges may be absent.
 * @property changeType Tree operation associated with this path pair.
 * @property hunks Paired edit coordinates, preserving empty insertion or deletion sides.
 * @property similarityScore Git similarity percentage for a rename or copy, otherwise absent.
 */
data class ChangedFile(
    val basePath: String?,
    val headPath: String?,
    val baseLines: List<ChangedLineRange>,
    val headLines: List<ChangedLineRange>,
    val binary: Boolean = false,
    val changeType: GitChangeType = GitChangeType.MODIFY,
    val hunks: List<ChangedHunk> = emptyList(),
    /** Git similarity percentage for a rename or copy, otherwise absent. */
    val similarityScore: Int? = null,
)

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
    fun changes(): List<ChangedFile> {
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
