package io.johnsonlee.graphfold.cli

import com.github.ajalt.clikt.testing.test
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.johnsonlee.graphfold.engine.AnalysisMetadata
import io.johnsonlee.graphfold.engine.AnalysisRequest
import io.johnsonlee.graphfold.engine.AnalysisResult
import io.johnsonlee.graphfold.engine.AnalysisStage
import io.johnsonlee.graphfold.engine.Diagnostic
import io.johnsonlee.graphfold.engine.Finding
import io.johnsonlee.graphfold.engine.FindingKind
import io.johnsonlee.graphfold.engine.Revision
import io.johnsonlee.graphfold.engine.SourceLocation
import io.johnsonlee.graphfold.engine.SourceRange
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GraphFoldCommandTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `pass reports and transfers all inputs`() {
        var captured: AnalysisRequest? = null
        val result = GraphFoldCommand { configuration, request ->
            assertEquals(directory.resolve("cache"), configuration.cacheDirectory)
            captured = request
            result()
        }.test(arguments("json"))
        assertEquals(0, result.statusCode)
        assertEquals("PASS", jacksonObjectMapper().readTree(result.stdout)["verdict"].asText())
        assertEquals(AnalysisRequest("repo", "base", "head", "folds: []"), captured)
    }

    @Test
    fun `violation returns one and precise text locations`() {
        val finding = Finding(
            "id", FindingKind.UNCONTROLLED_CHANGE, "rule", "Changed return value",
            listOf(SourceLocation(Revision.HEAD, "Example.java", SourceRange(2, 2), SourceRange(3, 8))),
        )
        val output = GraphFoldCommand { _, _ -> result(findings = listOf(finding)) }.test(arguments("text"))
        assertEquals(1, output.statusCode)
        assertTrue(output.stdout.contains("HEAD Example.java:2:3-2:8"))
    }

    @Test
    fun `operational diagnostics return two`() {
        for (stage in listOf(AnalysisStage.INPUT, AnalysisStage.GIT, AnalysisStage.BUILD)) {
            val output = GraphFoldCommand { _, _ -> result(listOf(Diagnostic(stage, "error", "Cannot proceed"))) }
                .test(arguments("json"))
            assertEquals(2, output.statusCode)
        }
    }

    @Test
    fun `analysis gaps return one while informational errors do not block`() {
        val blocked = GraphFoldCommand { _, _ -> result(listOf(Diagnostic(AnalysisStage.GRAPH, "gap", "Unsupported"))) }
            .test(arguments("text"))
        assertEquals(1, blocked.statusCode)
        val info = GraphFoldCommand { _, _ -> result(listOf(Diagnostic(AnalysisStage.GIT, "info", "Cached", false))) }
            .test(arguments("text"))
        assertEquals(0, info.statusCode)
        assertTrue(info.stdout.contains("GIT/info: Cached"))
    }

    @Test
    fun `invalid inputs and IO errors fail with two`() {
        val command = GraphFoldCommand { _, _ -> throw IOException("Cannot read repository") }
        assertEquals(2, command.test(arguments("text")).statusCode)
        val invalid = GraphFoldCommand { _, _ -> throw IllegalArgumentException("Invalid rule") }
        assertEquals(2, invalid.test(arguments("json")).statusCode)
        assertEquals(2, executeCli(emptyArray()))
    }

    @Test
    fun `default command invokes engine and returns structured failure`() {
        val output = GraphFoldCommand().test(arguments("json"))
        assertEquals(2, output.statusCode)
        assertEquals("FAIL", jacksonObjectMapper().readTree(output.stdout)["verdict"].asText())
    }

    @Test
    fun `help documents inputs without analysis`() {
        val output = GraphFoldCommand { _, _ -> error("Must not analyze") }.test("--help")
        assertEquals(0, output.statusCode)
        assertTrue(output.stdout.contains("--fold"))
        assertEquals(0, executeCli(arrayOf("--help")))
        assertEquals(0, executeCli(arguments("text").toTypedArray(), GraphFoldCommand { _, _ -> result() }))
        assertEquals(2, executeCli(arguments("text").toTypedArray(), GraphFoldCommand { _, _ ->
            result(listOf(Diagnostic(AnalysisStage.GIT, "missing", "Missing repository")))
        }))
    }

    private fun arguments(format: String): List<String> {
        val fold = directory.resolve("fold.yml").apply { writeText("folds: []") }
        return listOf("--repository", "repo", "--base", "base", "--head", "head", "--fold", fold.toString(),
            "--cache-dir", directory.resolve("cache").toString(), "--format", format)
    }

    private fun result(diagnostics: List<Diagnostic> = emptyList(), findings: List<Finding> = emptyList()) = AnalysisResult(
        findings, diagnostics, AnalysisMetadata("base", "head", listOf("baseline"), 0, 0),
    )
}
