package io.johnsonlee.graphfold.engine

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ResultReasonIntegrationTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `checked in source fixtures explain passing and failing verdicts`() {
        val fixtures = Path.of("../fixtures").toAbsolutePath().normalize()
        val cases = jacksonObjectMapper().readValue<List<ReasonCase>>(fixtures.resolve("reasons/manifest.json").toFile())
        cases.forEach { case ->
            val result = analyze(fixtures, case.caseId)
            assertTrue(result.diagnostics.none { it.blocking }, "${case.caseId}: ${result.diagnostics}")
            assertEquals(case.verdict, result.verdict, case.caseId)
            assertTrue(result.reasons.any { it.code == case.code && case.change in it.changes },
                "${case.caseId}: ${result.reasons}")
            assertTrue(result.reasons.all { it.locations.isNotEmpty() && it.scenario.isNotBlank() }, case.caseId)
            if (case.line != null) verifyCoordinates(case, result)
        }
    }

    private fun verifyCoordinates(case: ReasonCase, result: AnalysisResult) {
        val path = "src/main/java/sample/Example.java"
        val line = SourceRange(requireNotNull(case.line), case.line)
        val expected = listOf(
            SourceLocation(Revision.BASE, path, line, SourceRange(requireNotNull(case.baseStart), requireNotNull(case.baseEnd))),
            SourceLocation(Revision.HEAD, path, line, SourceRange(requireNotNull(case.headStart), requireNotNull(case.headEnd))),
        )
        assertEquals(expected, result.findings.single().locations, case.caseId)
        assertEquals(expected, result.reasons.single().locations, case.caseId)
        assertEquals(path, result.reasons.single().basePath)
        assertEquals(path, result.reasons.single().headPath)
    }

    private fun analyze(fixtures: Path, id: String): AnalysisResult {
        val repository = Files.createDirectory(temporary.resolve(id))
        git(repository, "init", "--quiet")
        git(repository, "config", "user.name", "GraphFold Test")
        git(repository, "config", "user.email", "test@example.invalid")
        val sources = Files.createDirectories(repository.resolve("src/main/java/sample"))
        Files.copy(fixtures.resolve("common/Ab.java"), sources.resolve("Ab.java"))
        Files.copy(fixtures.resolve("cases/$id/base/Example.java"), sources.resolve("Example.java"))
        git(repository, "add", ".")
        git(repository, "commit", "--quiet", "-m", "base")
        val base = git(repository, "rev-parse", "HEAD").trim()
        Files.copy(fixtures.resolve("cases/$id/head/Example.java"), sources.resolve("Example.java"), StandardCopyOption.REPLACE_EXISTING)
        git(repository, "add", ".")
        git(repository, "commit", "--quiet", "-m", "head")
        val head = git(repository, "rev-parse", "HEAD").trim()
        return GraphFoldEngine(EngineConfiguration(temporary.resolve("cache"))).analyze(
            AnalysisRequest(repository.toString(), base, head, Files.readString(fixtures.resolve("fold.yml"))),
        )
    }

    private fun git(directory: Path, vararg arguments: String): String {
        val process = ProcessBuilder(listOf("git") + arguments).directory(directory.toFile()).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { output }
        return output
    }

    data class ReasonCase(
        val caseId: String, val verdict: Verdict, val code: ReasonCode, val change: ChangeKind,
        val line: Int? = null, val baseStart: Int? = null, val baseEnd: Int? = null,
        val headStart: Int? = null, val headEnd: Int? = null,
    )
}
