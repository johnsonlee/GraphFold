package io.johnsonlee.graphfold.engine

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PreparationFailureReportTest {
    private val fold = """
        scenarios:
          - name: disabled
            folds:
              - {select: 'MATCH (c:CallSite) RETURN c', value: false}
              - {match: {CallSite: {callee_name: enabled}}, value: false}
    """.trimIndent()
    private val unavailable = Diagnostic(AnalysisStage.BUILD, DiagnosticCode.ANALYSIS_FAILED, "Archive unavailable")

    @Test fun `preparation failure preserves controls without pretending graph selection ran`() {
        FoldDocument.validate(fold, 1)
        val result = AnalysisReports.preparationFailure("base", "head", fold, 1, unavailable)
        assertEquals(Verdict.FAIL, result.verdict)
        assertEquals(listOf(unavailable), result.diagnostics)
        assertEquals(AnalysisMetadata("base", "head", listOf("disabled"), 0, 0), result.metadata)
        assertTrue(result.findings.isEmpty())
        assertEquals(4, result.ruleAudits.size)
        Revision.entries.forEach { revision ->
            val audits = result.ruleAudits.filter { it.revision == revision }
            assertEquals(listOf(RuleSelectorKind.CYPHER, RuleSelectorKind.CALL_SITE_MATCH), audits.map { it.selector.kind })
            audits.forEach {
                assertEquals(RuleSelectionStatus.NOT_EXECUTED, it.selectionStatus)
                assertNull(it.matchedCallCount)
                assertTrue(it.selectedNodes.isEmpty())
            }
        }
    }

    @Test fun `invalid control document takes precedence over preparation failure`() {
        val invalid = "folds: []"
        assertFailsWith<IllegalArgumentException> { FoldDocument.validate(invalid, 1) }
        val result = AnalysisReports.preparationFailure("base", "head", invalid, 1, unavailable)
        assertEquals(Verdict.FAIL, result.verdict)
        assertEquals(AnalysisStage.INPUT, result.diagnostics.single().stage)
        assertEquals(DiagnosticCode.ANALYSIS_FAILED, result.diagnostics.single().code)
        assertTrue(result.ruleAudits.isEmpty())
        assertTrue(result.metadata.scenarios.isEmpty())
    }
}
