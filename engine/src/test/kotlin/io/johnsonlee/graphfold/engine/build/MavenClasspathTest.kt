package io.johnsonlee.graphfold.engine.build

import java.io.File
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MavenClasspathTest {
    @TempDir lateinit var temporary: Path

    @Test fun `empty and whitespace-only manifests do not import the working directory`() {
        val manifest = temporary.resolve("classpath.txt")
        for (text in listOf("", " \n\t ", File.pathSeparator.repeat(2))) {
            Files.writeString(manifest, text)
            assertEquals(emptyList(), readMavenClasspath(manifest))
        }
    }

    @Test fun `blank separators are skipped while dependency order and path spaces remain intact`() {
        val first = temporary.resolve("first dependency.jar")
        val second = temporary.resolve("second.jar")
        val text = listOf("", first.toString(), "", " \t", second.toString(), first.toString(), "")
            .joinToString(File.pathSeparator)
        val manifest = Files.writeString(temporary.resolve("classpath.txt"), text)
        assertEquals(listOf(first, second, first), readMavenClasspath(manifest))
    }

    @Test fun `missing manifest is not an empty dependency export`() {
        assertFailsWith<NoSuchFileException> { readMavenClasspath(temporary.resolve("missing.txt")) }
    }
}
