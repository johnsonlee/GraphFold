package io.johnsonlee.graphfold.engine

import io.johnsonlee.graphfold.engine.build.KotlinBuildAdapter
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
        val differences = SnapshotComparison(old, new).differences(obligation)
        assertTrue(
            differences.isNotEmpty() || old.diagnostics.isNotEmpty() || new.diagnostics.isNotEmpty(),
            "Changed externally observed private field name must retain a difference or a blocking analysis gap",
        )
    }

    private fun application(fixtures: Path, revision: String, language: String, jar: Path): Application {
        val output = Files.createDirectories(temporary.resolve(revision))
        if (language == "java") return Application(compile(fixtures.resolve(revision), output, jar), listOf(jar))
        val classes = Files.createDirectories(output.resolve("classes"))
        val sources = listOf(
            fixtures.resolve("kotlin/$revision/sample/Example.kt"),
            fixtures.resolve("library/dependency/Encoder.java"),
        )
        val dependencies = KotlinBuildAdapter(Duration.ofMinutes(2)).compile(fixtures, output, classes, sources)
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
