package io.johnsonlee.graphfold.engine

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AnalysisReportsTest {
    private val mapper = jacksonObjectMapper()

    @Test
    fun `report includes structured and human readable precise findings`() {
        val finding = Finding(
            "finding-1", FindingKind.UNCONTROLLED_CHANGE, "fold-survival", "Return value changed",
            listOf(SourceLocation(Revision.HEAD, "Example.java", SourceRange(2, 3), SourceRange(4, 7))),
        )
        val result = AnalysisResult(
            listOf(finding), listOf(Diagnostic(AnalysisStage.GRAPH, DiagnosticCode.INCOMPLETE_GRAPH, "Unresolved target")),
            AnalysisMetadata("base", "head", listOf("baseline"), 1, 0),
        )
        val json = jacksonObjectMapper().readTree(AnalysisReports.json(result))
        assertEquals("FAIL", json["verdict"].asText())
        assertEquals("INCOMPLETE_GRAPH", json["diagnostics"][0]["code"].asText())
        assertEquals(4, json["findings"][0]["locations"][0]["column"]["start"].asInt())
        val text = AnalysisReports.text(result)
        assertTrue(text.contains("Proved 0/1 change obligations"))
        assertTrue(text.contains("HEAD Example.java:2:4-3:7"))
        assertTrue(text.contains("GRAPH/INCOMPLETE_GRAPH: Unresolved target"))
    }

    @Test
    fun `empty successful report contains verdict`() {
        val result = AnalysisResult(emptyList(), emptyList(), AnalysisMetadata("base", "head", emptyList(), 0, 0))
        assertEquals("GraphFold: PASS\nProved 0/0 change obligations", AnalysisReports.text(result))
    }

    @Test
    fun `default summary stays small for broad selectors while JSON retains every node`() {
        val nodes = List(10_000) { index ->
            SelectedCallSite(index, SelectedMethod("App.run()", "()V"), SelectedMethod("Flags.enabled()", "()Z"),
                index, null, index + 1)
        }
        val audit = RuleAudit("control", Revision.HEAD, 2,
            RuleSelector(RuleSelectorKind.CYPHER, "MATCH (n) RETURN n", "all calls"),
            RuleSelectionStatus.SELECTED, nodes, 9_999)
        val result = report(listOf(audit))
        val summary = AnalysisReports.foldSelectionSummary(result).single()
        assertEquals("[graphfold.fold] control/HEAD/rule#2: SELECTED 10000 sites, 9999 matched", summary)
        val text = AnalysisReports.text(result)
        assertEquals(1, text.lineSequence().count { it == summary })
        assertTrue(text.length < 200)
        assertFalse(text.contains("App.run"))
        assertFalse(text.contains("MATCH (n)"))
        val fullAudit = mapper.readTree(AnalysisReports.foldSelectionLog(result).single().removePrefix("[graphfold.fold] "))
        assertEquals(10_000, fullAudit["selectedNodes"].size())
        assertEquals(9_999, fullAudit["selectedNodes"].last()["nodeId"].asInt())
        assertEquals(fullAudit, mapper.readTree(AnalysisReports.json(result))["ruleAudits"][0])
    }

    @Test
    fun `summary distinguishes unavailable counts from successful empty selection and partial fold`() {
        val pending = RuleAudit("control", Revision.BASE, 0,
            RuleSelector(RuleSelectorKind.CALL_SITE_MATCH, null, "match"))
        val audits = listOf(pending, pending.copy(selectionStatus = RuleSelectionStatus.FAILED),
            pending.copy(selectionStatus = RuleSelectionStatus.SELECTED, matchedCallCount = 0),
            pending.copy(selectionStatus = RuleSelectionStatus.SELECTED))
        assertEquals(listOf(
            "[graphfold.fold] control/BASE/rule#0: NOT_EXECUTED unknown sites, unknown matched",
            "[graphfold.fold] control/BASE/rule#0: FAILED unknown sites, unknown matched",
            "[graphfold.fold] control/BASE/rule#0: SELECTED 0 sites, 0 matched",
            "[graphfold.fold] control/BASE/rule#0: SELECTED 0 sites, unknown matched",
        ), AnalysisReports.foldSelectionSummary(report(audits)))
        assertEquals(emptyList(), AnalysisReports.foldSelectionSummary(report(emptyList())))
        assertEquals(emptyList(), AnalysisReports.foldSelectionLog(report(emptyList())))
    }

    private fun report(audits: List<RuleAudit>) = AnalysisResult(
        emptyList(), emptyList(), AnalysisMetadata("base", "head", listOf("control"), 0, 0), ruleAudits = audits,
    )
}
