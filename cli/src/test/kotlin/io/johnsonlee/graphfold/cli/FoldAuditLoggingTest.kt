package io.johnsonlee.graphfold.cli

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.github.ajalt.clikt.testing.test
import io.johnsonlee.graphfold.engine.AnalysisMetadata
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
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class FoldAuditLoggingTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `selection log contains every selected node and keeps stdout valid JSON`() {
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
            "--repository", "repo", "--base", "base", "--head", "head", "--fold", rules.toString(), "--format", "json",
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
}
