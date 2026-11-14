package io.johnsonlee.graphfold.engine

import io.johnsonlee.graphfold.engine.graphite.GraphiteAnalyzer
import io.johnsonlee.graphfold.engine.source.ProofObligation
import io.johnsonlee.graphfold.engine.source.SymbolKind
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RetainingDependencyIntegrationTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `retained application body changes remain uncontrolled without modeling later println behavior`() {
        for (expression in listOf("new dependency.Wrapper(new AppThing())", "dependency.Wrapper.wrap(new AppThing())")) {
            val library = library()
            val applications = listOf("before", "after").map { application(it, expression, library) }
            assertEquals("before", execute(applications[0], library))
            assertEquals("after", execute(applications[1], library))
            val snapshots = applications.map { GraphiteAnalyzer().analyze(it, emptyList(), listOf(library)) }
            val comparison = SnapshotComparison(snapshots[0], snapshots[1])
            val obligation = ProofObligation("sample.AppThing.toString()Ljava/lang/String;", SymbolKind.METHOD,
                "sample.AppThing", emptyList())
            assertTrue(snapshots.all { snapshot -> snapshot.methodDiagnostics.values.flatten().any {
                "object graph escape" in it.message && "Wrapper" in it.message
            } }, "The observation evidence must remain available")
            assertTrue(comparison.analysisGaps(obligation).isEmpty())
            assertTrue(obligation.identity in comparison.differences(obligation))
            assertTrue(obligation.identity in comparison.wholeProgramDifferences())
        }
    }

    @Test
    fun `verified no op consumer of application payload does not introduce an escape guard`() {
        val library = library()
        val application = compile("Example", """
            package sample;
            public class Example {
                public String run() { dependency.Wrapper.ignore(new AppThing()); return "stable"; }
            }
            final class AppThing { public String toString() { return "ignored"; } }
        """.trimIndent(), library)
        assertEquals("stable", execute(application, library))
        val snapshot = GraphiteAnalyzer().analyze(application, emptyList(), listOf(library))
        val comparison = SnapshotComparison(snapshot, snapshot)
        val obligation = ProofObligation("sample.AppThing.toString()Ljava/lang/String;", SymbolKind.METHOD,
            "sample.AppThing", emptyList())
        assertTrue(comparison.analysisGaps(obligation).none { "object graph escape" in it || "dependency observation" in it })
    }

    private fun library(): Path = compile("Wrapper", """
        package dependency;
        public final class Wrapper {
            private final Object value;
            public Wrapper(Object value) { this.value = value; }
            public static Wrapper wrap(Object value) { return new Wrapper(value); }
            public static void ignore(Object value) { }
            public String toString() { return value.toString(); }
        }
    """.trimIndent())

    private fun application(value: String, expression: String, library: Path): Path = compile("Example", """
        package sample;
        public class Example {
            public String run() {
                java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
                new java.io.PrintStream(bytes).println($expression);
                return bytes.toString().trim();
            }
        }
        final class AppThing {
            public String toString() { return "$value"; }
        }
    """.trimIndent(), library)

    private fun compile(name: String, source: String, library: Path? = null): Path {
        val directory = Files.createTempDirectory(temporary, name)
        val input = directory.resolve("$name.java")
        val output = Files.createDirectories(directory.resolve("classes"))
        Files.writeString(input, source)
        val options = listOf("--release", "17", "-g", "-d", output.toString()) +
            library?.let { listOf("-classpath", it.toString()) }.orEmpty() + input.toString()
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, *options.toTypedArray()))
        return output
    }

    private fun execute(application: Path, library: Path): Any? = URLClassLoader(
        arrayOf(application.toUri().toURL(), library.toUri().toURL()), ClassLoader.getPlatformClassLoader(),
    ).use { loader ->
        val type = loader.loadClass("sample.Example")
        type.getMethod("run").invoke(type.getConstructor().newInstance())
    }
}
