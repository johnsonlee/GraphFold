package io.johnsonlee.graphfold.engine

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AdditionIntegrationTest {
    @TempDir lateinit var temporary: Path

    @Test fun `new unused Java source file passes`() { verifyFixture("java_unused_file") }
    @Test fun `new referenced Java source file changes production behavior`() { verifyFixture("java_used_file") }
    @Test fun `new unused Kotlin source file passes`() { verifyFixture("kotlin_unused_file") }
    @Test fun `new referenced Kotlin source file changes production behavior`() { verifyFixture("kotlin_used_file") }

    private fun verifyFixture(id: String) {
        val fixtures = Path.of("../fixtures/additions")
        val fixture = jacksonObjectMapper().readValue<List<AdditionCase>>(
            fixtures.resolve("manifest.json").toFile(),
        ).single { it.id == id }
        assertTrue(!Files.exists(fixtures.resolve("cases/$id/base/${fixture.addedPath}")))
        assertTrue(Files.exists(fixtures.resolve("cases/$id/head/${fixture.addedPath}")))
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
        assertTrue(result.reasons.any { it.headPath == fixture.addedPath }, result.toString())
        if (fixture.violation) {
            assertTrue(result.findings.isNotEmpty(), result.toString())
            result.findings.flatMap { it.locations }.forEach { location ->
                val revision = if (location.revision == Revision.BASE) "base" else "head"
                assertTrue(Files.exists(fixtures.resolve("cases/$id/$revision/${location.path}")), location.toString())
            }
        } else {
            assertTrue(result.findings.isEmpty(), result.toString())
            assertTrue(result.reasons.filter { it.headPath == fixture.addedPath }.all {
                it.code == ReasonCode.UNREACHABLE
            }, result.toString())
        }
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

    data class AdditionCase(val id: String, val violation: Boolean, val addedPath: String)
}
