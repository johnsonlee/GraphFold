package io.johnsonlee.graphfold.engine

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.assertFalse

class CleanupTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `cleanup removes temporary files and tolerates repeat calls`() {
        val directory = Files.createDirectory(temporary.resolve("run"))
        Files.writeString(directory.resolve("artifact"), "temporary")
        cleanupDirectory(directory)
        cleanupDirectory(directory)
        assertFalse(Files.exists(directory))
    }

    @Test
    fun `cleanup failures do not replace the verdict or build failure`() {
        listOf(IOException("file is held"), java.io.UncheckedIOException(IOException("cannot traverse")),
            SecurityException("access denied")).forEach { failure ->
            cleanupDirectory(temporary) { throw failure }
        }
    }
}
