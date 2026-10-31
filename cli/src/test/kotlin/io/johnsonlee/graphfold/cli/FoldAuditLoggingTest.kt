package io.johnsonlee.graphfold.cli

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.github.ajalt.clikt.testing.test
import io.johnsonlee.graphfold.engine.AnalysisMetadata
import io.johnsonlee.graphfold.engine.AnalysisReports
import io.johnsonlee.graphfold.engine.AnalysisResult
import io.johnsonlee.graphfold.engine.Revision
import io.johnsonlee.graphfold.engine.RuleAudit
import io.johnsonlee.graphfold.engine.RuleSelectionStatus
import io.johnsonlee.graphfold.engine.RuleSelector
import io.johnsonlee.graphfold.engine.RuleSelectorKind
import io.johnsonlee.graphfold.engine.SelectedCallSite
import io.johnsonlee.graphfold.engine.SelectedMethod
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class FoldAuditLoggingTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `verbose selection log contains every selected node and keeps stdout valid JSON`() {
        val selected = listOf(
            SelectedCallSite(17, SelectedMethod("sample.App.run()", "()V"),
                SelectedMethod("sample.Flags.enabled(int)", "(I)Z"), 0, null, 12),
            SelectedCallSite(29, SelectedMethod("sample.App.other()", "()V"),
                SelectedMethod("sample.Flags.enabled(int)", "(I)Z"), 1, 17, 24),
        )
        val audit = RuleAudit(
            "control", Revision.HEAD, 0,
            RuleSelector(RuleSelectorKind.CYPHER, "MATCH (c:CallSite)\nRETURN c", "select flag calls"),
            RuleSelectionStatus.SELECTED, selected, matchedCallCount = 1,
        )
        val analysis = AnalysisResult(emptyList(), emptyList(),
            AnalysisMetadata("base", "head", listOf("control"), 0, 0), ruleAudits = listOf(audit))
        val rules = directory.resolve("fold.yml").apply { writeText("folds: []") }
        val output = GraphFoldCommand { _, _ -> analysis }.test(listOf(
            "--repository", "repo", "--base", "base", "--head", "head", "--fold", rules.toString(), "--format", "json", "--verbose",
        ))
        assertEquals(0, output.statusCode, output.output)
        val mapper = jacksonObjectMapper()
        val report = mapper.readTree(output.stdout)
        assertEquals("PASS", report["verdict"].asText())
        val line = output.stderr.trim().lines().single()
        assertTrue(line.startsWith("[graphfold.fold]"), line)
        val logged = mapper.readTree(line.removePrefix("[graphfold.fold]").trim())
        assertEquals(report["ruleAudits"][0], logged)
        assertEquals(2, logged["selectedCount"].asInt())
        assertEquals(1, logged["matchedCallCount"].asInt())
        assertEquals(listOf(17, 29), logged["selectedNodes"].map { it["nodeId"].asInt() })
        assertEquals("(I)Z", logged["selectedNodes"][0]["callee"]["descriptor"].asText())
        assertEquals(17, logged["selectedNodes"][1]["originNodeId"].asInt())
        assertEquals(24, logged["selectedNodes"][1]["lineNumber"].asInt())
    }

    @ParameterizedTest
    @CsvSource("text,false", "text,true", "json,false", "json,true")
    fun `large selections use bounded summaries unless verbose logging is requested`(format: String, verbose: Boolean) {
        val selected = List(2_000) { index ->
            SelectedCallSite(index, SelectedMethod("sample.App.run()", "()V"),
                SelectedMethod("sample.Flags.enabled(int)", "(I)Z"), index, null, index + 1)
        }
        val audit = RuleAudit(
            "control", Revision.HEAD, 0,
            RuleSelector(RuleSelectorKind.CYPHER, "MATCH (c:CallSite) RETURN c", "select flag calls"),
            RuleSelectionStatus.SELECTED, selected, matchedCallCount = selected.size,
        )
        val analysis = AnalysisResult(emptyList(), emptyList(),
            AnalysisMetadata("base", "head", listOf("control"), 0, 0), ruleAudits = listOf(audit))
        val rules = directory.resolve("fold.yml").apply { writeText("folds: []") }
        val arguments = listOf(
            "--repository", "repo", "--base", "base", "--head", "head", "--fold", rules.toString(), "--format", format,
        ) + if (verbose) listOf("--verbose") else emptyList()
        val output = GraphFoldCommand { _, _ -> analysis }.test(arguments)
        assertEquals(0, output.statusCode, output.output)
        val summary = "[graphfold.fold] control/HEAD/rule#0: SELECTED 2000 sites, 2000 matched"
        if (format == "json") {
            val report = jacksonObjectMapper().readTree(output.stdout)
            assertEquals(selected.size, report["ruleAudits"][0]["selectedNodes"].size())
            assertEquals("sample.App.run()", report["ruleAudits"][0]["selectedNodes"][0]["caller"]["signature"].asText())
        } else {
            assertTrue(output.stdout.contains(summary), output.stdout)
            assertFalse(output.stdout.contains("sample.App"), output.stdout)
            assertFalse(output.stdout.contains("MATCH"), output.stdout)
            assertTrue(output.stdout.length < 500, "Text report must stay bounded as node count grows")
            assertEquals(1, output.stdout.lineSequence().count { it.startsWith("[graphfold.fold]") })
        }
        if (verbose) {
            assertEquals(AnalysisReports.foldSelectionLog(analysis), output.stderr.trim().lines())
            val logged = jacksonObjectMapper().readTree(output.stderr.removePrefix("[graphfold.fold]").trim())
            assertEquals(selected.size, logged["selectedNodes"].size())
        } else {
            assertEquals(if (format == "json") summary else "", output.stderr.trim())
        }
    }
}
