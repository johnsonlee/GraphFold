package io.johnsonlee.graphfold.engine.graphite

import io.johnsonlee.graphite.input.LoaderConfig
import io.johnsonlee.graphite.sootup.JavaProjectLoader
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GraphiteDiagnosticsTest {
    @TempDir lateinit var directory: Path

    @Test fun `artifact collector preserves failures without accepting embedded application payloads`() {
        val diagnostics = mutableListOf<String>()
        val collect = artifactDiagnosticCollector(diagnostics)
        val failure = "Failed to extract artifact dependencies from broken.jar: invalid metadata"
        collect(failure)
        collect("Loaded an enum value: $failure")
        collect("Warning: Recovered body for a method")
        assertEquals(listOf(failure), diagnostics)
    }

    @Test
    fun `owned failure and recovery records remain incomplete diagnostics`() {
        listOf(
            "Skipping method due to OOM while building sample.Example.run(): exhausted",
            "Skipping method sample.Example.run()V: OOM during streaming resolution",
            "Skipping method <sample.Example: void run()>: invalid body",
            "Skipping methods for sample.Example: OOM during method resolution",
            "Skipping methods for sample.Example: invalid state",
            "Skipping control-flow extraction for sample.Example.run(): invalid body",
            "Failed to extract artifact dependencies from library.jar!/sample.Example: unreadable class",
            "Failed to extract artifact dependencies from library.jar!/sample/Example.class: unreadable class",
            "Warning: Recovered body for <sample.Example: void run()> using defaults: invalid body",
            "Warning: Recovered 1 APK method body/bodies after interceptor failures",
        ).forEach { message -> assertTrue(isGraphiteIncompleteDiagnostic(message), message) }
    }

    @Test
    fun `application payloads cannot impersonate diagnostic records`() {
        listOf(
            "",
            "Loaded 12 classes",
            "  Extracted enum value: sample.Message.FAILURE = [Failed to reconstruct type %s, 0]",
            "    Found <init> for local with 2 args: [FAILURE, Failed to get changed files]",
            "  Extracted enum value: sample.Message.SKIPPED = [Skipping method fake: fake]",
            "  Extracted enum value: sample.Message.FLOW = [Skipping control-flow extraction for fake: fake]",
            "  Extracted enum value: sample.Message.RECOVERY = [Recovered body for fake: fake]",
            "  Extracted enum value: sample.Message.MULTILINE = [prefix\nWarning: Recovered body for fake: fake]",
            "Found constant: Failed to extract artifact dependencies from fake: fake",
            "    Found <init> args: [prefix\nSkipping method fake: fake]",
            "  Skipping method fake: indented payload",
            "\nWarning: Recovered body for fake: payload",
        ).forEach { message -> assertFalse(isGraphiteIncompleteDiagnostic(message), message) }
    }

    @Test
    fun `real enum extraction retains user diagnostic strings as ordinary verbose data`() {
        val fixtures = Path.of("../fixtures").toAbsolutePath().normalize()
        val classes = Files.createDirectory(directory.resolve("classes"))
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
            "-g", "-d", classes.toString(), fixtures.resolve("common/Ab.java").toString(),
            fixtures.resolve("frontend-diagnostics/java/controlled/base/Example.java").toString()))
        val messages = mutableListOf<String>()
        JavaProjectLoader(LoaderConfig(verbose = messages::add), false).load(classes)
        val extracted = messages.filter { it.startsWith("  Extracted enum value:") }
        assertTrue(extracted.isNotEmpty(), "The regression must exercise Graphite's enum log path")
        for (needle in listOf("Failed to", "Skipping method", "Skipping control-flow", "Recovered ")) {
            assertTrue(extracted.any { needle in it }, "Missing enum payload: $needle")
        }
        assertTrue(messages.none(::isGraphiteIncompleteDiagnostic), messages.toString())
        val snapshot = GraphiteAnalyzer().analyze(classes, emptyList())
        assertTrue(snapshot.diagnostics.isEmpty(), snapshot.diagnostics.toString())
    }
}
