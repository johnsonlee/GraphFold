package io.johnsonlee.graphfold.engine

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.net.URLClassLoader
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.time.Duration
import io.johnsonlee.graphfold.engine.build.ProjectCompiler
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InitializationIntegrationTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `Java production class initializes its default method superinterface`() {
        verifyFixture("java_default_interface")
    }

    @Test
    fun `Kotlin production class initializes its Java default method superinterface`() {
        verifyFixture("kotlin_default_interface")
    }

    @Test
    fun `adding unused Java default method activates interface initialization`() {
        verifyFixture("java_added_default_activates_initializer")
    }

    @Test
    fun `adding unused default method changes Kotlin class initialization prerequisites`() {
        verifyFixture("kotlin_added_default_activates_initializer")
    }

    private fun verifyFixture(id: String) {
        val fixtures = Path.of("../fixtures/initialization")
        val fixture = jacksonObjectMapper().readValue<List<InitializationCase>>(
            fixtures.resolve("manifest.json").toFile(),
        ).single { it.id == id }
        val repository = Files.createDirectory(temporary.resolve("repository"))
        git(repository, "init", "--quiet")
        git(repository, "config", "user.name", "GraphFold Test")
        git(repository, "config", "user.email", "test@example.invalid")
        copyTree(fixtures.resolve("cases/$id/base"), repository)
        val base = commit(repository)
        copyTree(fixtures.resolve("cases/$id/head"), repository)
        val head = commit(repository)
        val result = GraphFoldEngine(EngineConfiguration(temporary.resolve("cache"))).analyze(
            AnalysisRequest(repository.toString(), base, head, Files.readString(Path.of("../fixtures/fold.yml"))),
        )
        assertEquals(fixture.violation, result.verdict == Verdict.FAIL, result.toString())
        assertTrue(result.diagnostics.isEmpty(), "An analysis gap is not successful detection: $result")
        assertTrue(result.findings.isNotEmpty(), result.toString())
        val locations = result.findings.flatMap { it.locations }.distinct()
        if (fixture.change == "added-default") {
            assertTrue(locations.all {
                it.revision == Revision.HEAD && it.path == fixture.path && it.line == SourceRange(5, 5)
            }, result.toString())
            assertEquals("", initializationOutput(fixtures.resolve("cases/$id/base"), "runtime-base"))
            assertEquals("marker", initializationOutput(fixtures.resolve("cases/$id/head"), "runtime-head").trim())
        } else {
            assertEquals(setOf(Revision.BASE, Revision.HEAD), locations.map { it.revision }.toSet())
            assertTrue(locations.all { it.path == fixture.path && it.line == SourceRange(4, 4) }, result.toString())
            locations.forEach { location ->
                val revision = if (location.revision == Revision.BASE) "base" else "head"
                val line = Files.readAllLines(fixtures.resolve("cases/$id/$revision/${fixture.path}"))[3]
                val changedLiteral = if (location.revision == Revision.BASE) "before" else "after"
                assertEquals(changedLiteral, line.substring(location.column.start - 1, location.column.end - 1))
            }
        }
    }

    private fun initializationOutput(source: Path, name: String): String {
        val compiled = ProjectCompiler(Duration.ofMinutes(2)).compile(
            source.toAbsolutePath().normalize(), temporary.resolve(name),
        )
        val output = ByteArrayOutputStream()
        val previous = System.out
        PrintStream(output).use { capture ->
            try {
                System.setOut(capture)
                URLClassLoader(
                    (listOf(compiled.classes) + compiled.dependencies).map { it.toUri().toURL() }.toTypedArray(),
                    ClassLoader.getPlatformClassLoader(),
                ).use { loader -> loader.loadClass("sample.Example").getConstructor().newInstance() }
            } finally {
                System.setOut(previous)
            }
        }
        return output.toString(Charsets.UTF_8)
    }

    private fun copyTree(source: Path, target: Path) {
        Files.walk(source).use { paths ->
            paths.filter { Files.isRegularFile(it) }.forEach { path ->
                val destination = target.resolve(source.relativize(path))
                Files.createDirectories(destination.parent)
                Files.copy(path, destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }

    private fun commit(repository: Path): String {
        git(repository, "add", ".")
        git(repository, "commit", "--quiet", "-m", "fixture")
        return git(repository, "rev-parse", "HEAD").trim()
    }

    private fun git(repository: Path, vararg arguments: String): String {
        val process = ProcessBuilder(listOf("git") + arguments)
            .directory(repository.toFile()).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { output }
        return output
    }

    data class InitializationCase(
        val id: String, val violation: Boolean, val path: String, val change: String = "literal",
    )
}
