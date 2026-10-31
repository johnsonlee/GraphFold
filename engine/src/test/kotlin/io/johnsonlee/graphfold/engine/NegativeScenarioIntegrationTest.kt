package io.johnsonlee.graphfold.engine

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NegativeScenarioIntegrationTest {
    @TempDir lateinit var temporary: Path

    @Test fun `Java multiple IDs preserve their control paths`() { verifyFixture("java_multiple_ids") }
    @Test fun `Kotlin multiple IDs preserve their control paths`() { verifyFixture("kotlin_multiple_ids") }
    @Test fun `Java A and NA both preserve baseline behavior`() { verifyFixture("java_control_groups") }
    @Test fun `Kotlin A and NA both preserve baseline behavior`() { verifyFixture("kotlin_control_groups") }
    @Test fun `Java AB and version keys combine without hiding surviving changes`() { verifyFixture("java_mixed_keys") }
    @Test fun `Kotlin AB and version keys combine without hiding surviving changes`() { verifyFixture("kotlin_mixed_keys") }

    private fun verifyFixture(id: String) {
        val fixtures = Path.of("../fixtures/negative-scenarios")
        val fixture = jacksonObjectMapper().readValue<List<ScenarioCase>>(
            fixtures.resolve("manifest.json").toFile(),
        ).single { it.id == id }
        val source = fixtures.resolve("cases/$id")
        val repository = Files.createDirectory(temporary.resolve("repository"))
        git(repository, "init", "--quiet")
        git(repository, "config", "user.name", "GraphFold Test")
        git(repository, "config", "user.email", "test@example.invalid")
        copyTree(source.resolve("base"), repository)
        val base = commit(repository)
        copyTree(source.resolve("head"), repository)
        val head = commit(repository)
        val engine = GraphFoldEngine(EngineConfiguration(temporary.resolve("cache")))
        fun analyze(fold: String) = engine.analyze(
            AnalysisRequest(repository.toString(), base, head, Files.readString(source.resolve(fold))),
        )
        val controlled = analyze("fold.yml")
        assertEquals(Verdict.PASS, controlled.verdict, controlled.toString())
        assertTrue(controlled.findings.isEmpty() && controlled.diagnostics.isEmpty(), controlled.toString())
        assertTrue(controlled.metadata.obligations > 0, "Negative must exercise actual changed code")
        assertEquals(controlled.metadata.obligations, controlled.metadata.provedObligations)
        assertEquals(fixture.scenarios, controlled.metadata.scenarios)
        assertTrue(controlled.reasons.all { it.code == ReasonCode.FOLDED_EQUIVALENT }, controlled.toString())
        // The same change must become visible when the changed path is enabled.
        val active = analyze("active.yml")
        assertEquals(Verdict.FAIL, active.verdict, active.toString())
        assertTrue(active.diagnostics.isEmpty(), "A diagnostic is not detection: $active")
        assertTrue(active.findings.isNotEmpty(), active.toString())
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

    data class ScenarioCase(val id: String, val scenarios: List<String>)
}
