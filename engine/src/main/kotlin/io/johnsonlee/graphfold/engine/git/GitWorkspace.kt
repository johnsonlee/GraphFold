package io.johnsonlee.graphfold.engine.git

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.LinkOption.NOFOLLOW_LINKS

/** A one-based inclusive changed line range. Empty sides of a hunk have no range. */
data class ChangedLineRange(val start: Int, val end: Int) {
    init {
        require(start > 0 && end >= start) { "Invalid changed line range" }
    }
}

data class ChangedHunk(val baseStart: Int, val baseCount: Int, val headStart: Int, val headCount: Int)

enum class GitChangeType { ADD, DELETE, MODIFY, TYPE_CHANGE, RENAME, COPY }

/** Paths are relative to the repository; ranges contain changed lines, never context. */
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

/** Exclusively leases two persistent private checkouts; closing retains their incremental build outputs. */
class GitWorkspace internal constructor(
    val baseDirectory: Path,
    val headDirectory: Path,
    val baseSha: String,
    val headSha: String,
    private val repository: Path,
    private val cleanup: () -> Unit,
) : AutoCloseable {
    private var closed = false

    @Synchronized
    fun changes(): List<ChangedFile> {
        check(!closed) { "Workspace is closed" }
        return diff(repository, baseSha, headSha)
    }

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
