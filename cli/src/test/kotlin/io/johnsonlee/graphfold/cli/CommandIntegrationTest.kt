package io.johnsonlee.graphfold.cli

import io.johnsonlee.graphfold.engine.testing.createFixture

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.github.ajalt.clikt.testing.test
import io.johnsonlee.graphfold.engine.AnalysisReports
import io.johnsonlee.graphfold.engine.AnalysisRequest
import io.johnsonlee.graphfold.engine.EngineConfiguration
import io.johnsonlee.graphfold.engine.GraphFoldEngine
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CommandIntegrationTest {
    @TempDir lateinit var directory: Path

    @ParameterizedTest
    @CsvSource("gated_return,0", "arithmetic,1")
    fun `CLI agrees with SDK on real nonempty Java changes`(name: String, exitCode: Int) {
        val fixture = createFixture(directory, name)
        val args = listOf("--repository", fixture.repository.toString(), "--base", fixture.base, "--head", fixture.head,
            "--fold", fixture.fold.toString(), "--cache-dir", directory.resolve("cli-cache").toString(), "--format", "json")
        val cli = GraphFoldCommand().test(args)
        assertEquals(exitCode, cli.statusCode, cli.output)
        val expected = GraphFoldEngine(EngineConfiguration(directory.resolve("sdk-cache"))).analyze(
            AnalysisRequest(fixture.repository.toString(), fixture.base, fixture.head, fixture.fold.readText()),
        )
        val mapper = jacksonObjectMapper()
        val actual = mapper.readTree(cli.stdout)
        // Graphite node IDs are scoped to one graph, so separate analyses need not allocate the same IDs.
        assertEquals(withoutNodeIds(mapper.readTree(AnalysisReports.json(expected))), withoutNodeIds(actual))
        assertTrue(actual["diagnostics"].isEmpty)
        val auditLines = cli.stderr.lineSequence().filter { it.startsWith("[graphfold.fold]") }.toList()
        assertTrue(auditLines.isNotEmpty(), cli.output)
        assertEquals(AnalysisReports.foldSelectionSummary(expected), auditLines)
        actual["ruleAudits"].forEach { audit ->
            val nodes = audit["selectedNodes"]
            assertTrue(nodes.all { it["nodeId"].isInt })
            assertEquals(nodes.size(), nodes.map { it["nodeId"].asInt() }.distinct().size)
        }
        if (exitCode != 0) {
            assertTrue(actual["findings"].size() > 0)
            val location = actual["findings"][0]["locations"][0]
            assertEquals("src/main/java/sample/Example.java", location["path"].asText())
            assertTrue(location["column"]["start"].asInt() > 0)
        }
    }

    private fun withoutNodeIds(report: JsonNode): JsonNode = report.deepCopy<JsonNode>().apply {
        get("ruleAudits").forEach { audit ->
            audit["selectedNodes"].forEach { node -> (node as ObjectNode).remove("nodeId") }
        }
    }
}
