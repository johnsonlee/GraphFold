package io.johnsonlee.graphfold.engine

import io.johnsonlee.graphfold.engine.testing.createFixture
import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RuleAuditIntegrationTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `zero selections on both revisions remain blocking with explicit zero audit counts`() {
        val query = "MATCH (cs:CallSite) WHERE cs.callee_name = 'missing' RETURN cs"
        val result = analyze("folds:\n  - select: \"$query\"\n    value: false\n")
        assertEquals(Verdict.FAIL, result.verdict)
        assertTrue(result.diagnostics.any { it.code == "UNMATCHED_RULE" })
        assertEquals(2, result.ruleAudits.size)
        assertTrue(result.ruleAudits.all {
            it.selectionStatus == RuleSelectionStatus.SELECTED && it.selectedCount == 0 && it.matchedCallCount == 0 &&
                it.selector.query == query && it.selector.kind == RuleSelectorKind.CYPHER
        })
    }

    @Test
    fun `later invalid query retains earlier selection and marks unexecuted head rules explicitly`() {
        val result = analyze("""
            folds:
              - select: "MATCH (cs:CallSite) WHERE cs.callee_name = 'enabled' RETURN cs"
                value: false
              - select: "MATCH (cs:CallSite) RETURN cs.callee_name"
                value: false
        """.trimIndent())
        assertEquals(Verdict.FAIL, result.verdict)
        assertEquals(4, result.ruleAudits.size)
        val selected = result.ruleAudits[0]
        assertEquals(RuleSelectionStatus.SELECTED, selected.selectionStatus)
        assertTrue(requireNotNull(selected.selectedCount) > 0)
        assertEquals(null, selected.matchedCallCount)
        val failed = result.ruleAudits[1]
        assertEquals(RuleSelectionStatus.FAILED, failed.selectionStatus)
        assertEquals(null, failed.selectedCount)
        assertTrue(failed.message.orEmpty().contains("CallSite"))
        assertTrue(result.ruleAudits.drop(2).all { it.selectionStatus == RuleSelectionStatus.NOT_EXECUTED })
    }

    private fun analyze(fold: String): AnalysisResult {
        val fixture = createFixture(temporary, "arithmetic")
        return GraphFoldEngine(EngineConfiguration(temporary.resolve("cache"))).analyze(
            AnalysisRequest(fixture.repository.toString(), fixture.base, fixture.head, fold),
        )
    }
}
