package io.johnsonlee.graphfold.engine

import io.johnsonlee.graphfold.engine.testing.KotlinFixtureCompiler
import io.johnsonlee.graphfold.engine.graphite.GraphiteAnalyzer
import io.johnsonlee.graphfold.engine.source.ProofObligation
import io.johnsonlee.graphfold.engine.source.SymbolKind
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.tools.ToolProvider
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReflectiveDependencyIntegrationTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `private Java field escaping to reflective dependency cannot be silently normalized away`() {
        verifyLanguage("java")
    }

    @Test
    fun `private Kotlin field escaping to reflective dependency cannot be silently normalized away`() {
        verifyLanguage("kotlin")
    }

    @Test
    fun `zero argument dependency can observe private application changes through reflection`() {
        val librarySource = Files.createDirectories(temporary.resolve("hidden-library-source"))
        Files.writeString(librarySource.resolve("Probe.java"), """
            package dependency;
            public final class Probe {
                public static String invoke() throws Exception {
                    var method = Class.forName("sample.Example").getDeclaredMethod("hidden");
                    method.setAccessible(true);
                    return (String) method.invoke(null);
                }
            }
        """.trimIndent())
        val library = compile(librarySource, temporary.resolve("hidden-library"))
        val applications = listOf("before", "after").map { value ->
            val source = Files.createDirectories(temporary.resolve("hidden-$value-source"))
            Files.writeString(source.resolve("Example.java"), """
                package sample;
                public class Example {
                    public String run() throws Exception { return dependency.Probe.invoke(); }
                    private static String hidden() { return "$value"; }
                }
            """.trimIndent())
            compile(source, temporary.resolve("hidden-$value"), library)
        }
        assertEquals("before", runtimeOutput(applications[0], listOf(library)))
        assertEquals("after", runtimeOutput(applications[1], listOf(library)))
        val snapshots = applications.map { GraphiteAnalyzer().analyze(it, emptyList(), listOf(library)) }
        val comparison = SnapshotComparison(snapshots[0], snapshots[1])
        val obligation = ProofObligation("sample.Example.hidden()Ljava/lang/String;", SymbolKind.METHOD,
            "sample.Example", emptyList())
        assertEquals(listOf(obligation.identity), comparison.differences(obligation),
            "The changed private declaration survives without selected control coverage")
        assertTrue(snapshots.all { snapshot -> snapshot.methodDiagnostics.values.flatten().any {
            it.externalObservationOnly && "dependency observation" in it.message && "Probe.invoke" in it.message
        } }, "The supplied reflective dependency must retain its independent observation evidence")
        assertTrue(comparison.analysisGaps(obligation).isEmpty(),
            "Literal application body comparison establishes this difference without predicting reflective execution")
    }

    @Test
    fun `pure dependency does not exempt an uncontrolled uncalled private change`() {
        val librarySource = Files.createDirectories(temporary.resolve("pure-library-source"))
        Files.writeString(librarySource.resolve("Probe.java"), """
            package dependency;
            public final class Probe {
                private static String value() { return "stable"; }
                public static String invoke() { return value(); }
            }
        """.trimIndent())
        val library = compile(librarySource, temporary.resolve("pure-library"))
        val applications = listOf("before", "after").map { value ->
            val source = Files.createDirectories(temporary.resolve("pure-$value-source"))
            Files.writeString(source.resolve("Example.java"), """
                package sample;
                public class Example {
                    public String run() { return dependency.Probe.invoke(); }
                    private static String hidden() { return "$value"; }
                }
            """.trimIndent())
            compile(source, temporary.resolve("pure-$value"), library)
        }
        applications.forEach { assertEquals("stable", runtimeOutput(it, listOf(library))) }
        val snapshots = applications.map { GraphiteAnalyzer().analyze(it, emptyList(), listOf(library)) }
        val comparison = SnapshotComparison(snapshots[0], snapshots[1])
        val obligation = ProofObligation("sample.Example.hidden()Ljava/lang/String;", SymbolKind.METHOD,
            "sample.Example", emptyList())
        assertEquals(listOf(obligation.identity), comparison.differences(obligation),
            "Equal observed output and no caller do not establish selected control coverage")
        assertTrue(snapshots.all { snapshot -> snapshot.methodDiagnostics.values.flatten().none {
            "dependency observation" in it.message && "Probe.invoke" in it.message
        } }, "The closed dependency body must not fabricate a reflective observation gap")
        assertTrue(comparison.analysisGaps(obligation).isEmpty(), "A closed bytecode effect proof must avoid the global guard")
    }

    private fun verifyLanguage(language: String) {
        val fixtures = Path.of("../fixtures/analysis-gaps/reflective-dependency").toAbsolutePath().normalize()
        val library = compile(fixtures.resolve("library"), temporary.resolve("library"))
        val jar = temporary.resolve("encoder.jar")
        ZipOutputStream(Files.newOutputStream(jar)).use { archive ->
            Files.walk(library).use { paths ->
                paths.filter { Files.isRegularFile(it) }.forEach { path ->
                    archive.putNextEntry(ZipEntry(library.relativize(path).toString().replace('\\', '/')))
                    Files.copy(path, archive)
                    archive.closeEntry()
                }
            }
        }
        val base = application(fixtures, "base", language, jar)
        val head = application(fixtures, "head", language, jar)
        assertEquals("amount=1", runtimeOutput(base.classes, base.dependencies))
        assertEquals("offset=1", runtimeOutput(head.classes, head.dependencies))
        val analyzer = GraphiteAnalyzer()
        val old = analyzer.analyze(base.classes, emptyList(), base.dependencies)
        val new = analyzer.analyze(head.classes, emptyList(), head.dependencies)
        val obligation = ProofObligation("sample.Example.amount:I", SymbolKind.FIELD, "sample.Example", emptyList())
        val comparison = SnapshotComparison(old, new)
        assertTrue(comparison.analysisGaps(obligation).isNotEmpty(),
            "Changed externally observed private state must retain a blocking gap scoped to this obligation")
    }

    private fun application(fixtures: Path, revision: String, language: String, jar: Path): Application {
        val output = Files.createDirectories(temporary.resolve(revision))
        if (language == "java") return Application(compile(fixtures.resolve(revision), output, jar), listOf(jar))
        val classes = Files.createDirectories(output.resolve("classes"))
        val sources = listOf(
            fixtures.resolve("kotlin/$revision/sample/Example.kt"),
            fixtures.resolve("library/dependency/Encoder.java"),
        )
        val dependencies = KotlinFixtureCompiler(Duration.ofMinutes(2)).compile(fixtures, output, classes, sources)
        return Application(classes, dependencies + listOf(jar))
    }

    private fun compile(source: Path, output: Path, dependency: Path? = null): Path {
        Files.createDirectories(output)
        val files = Files.walk(source).use { paths ->
            paths.filter { it.toString().endsWith(".java") }.map { it.toString() }.toList()
        }
        val options = listOf("--release", "17", "-g", "-d", output.toString()) +
            dependency?.let { listOf("-classpath", it.toString()) }.orEmpty() + files
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, *options.toTypedArray()))
        return output
    }

    private fun runtimeOutput(classes: Path, dependencies: List<Path>): Any? = URLClassLoader(
        (listOf(classes) + dependencies).map { it.toUri().toURL() }.toTypedArray(), ClassLoader.getPlatformClassLoader(),
    ).use { loader ->
        val type = loader.loadClass("sample.Example")
        type.getMethod("run").invoke(type.getConstructor().newInstance())
    }
    private data class Application(val classes: Path, val dependencies: List<Path>)
}
