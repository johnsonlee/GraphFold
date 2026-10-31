package io.johnsonlee.graphfold.engine

import io.johnsonlee.graphfold.engine.git.deleteDirectory
import java.io.IOException
import java.nio.file.Path
import java.util.logging.Level
import java.util.logging.Logger

/** Temporary artifact cleanup must never replace an analysis result or its original failure. */
internal fun cleanupDirectory(path: Path, delete: (Path) -> Unit = ::deleteDirectory) {
    try {
        delete(path)
    } catch (failure: IOException) {
        reportCleanupFailure(path, failure)
    } catch (failure: java.io.UncheckedIOException) {
        reportCleanupFailure(path, failure)
    } catch (failure: SecurityException) {
        reportCleanupFailure(path, failure)
    }
}

private fun reportCleanupFailure(path: Path, failure: Exception) {
    Logger.getLogger("io.johnsonlee.graphfold.engine").log(Level.WARNING, "Cannot remove temporary analysis files: $path", failure)
}
