package io.johnsonlee.graphfold.engine

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AnalysisReportsTest {
    @Test
    fun `report includes structured and human readable precise findings`() {
        val finding = Finding(
            "finding-1", FindingKind.UNCONTROLLED_CHANGE, "fold-survival", "Return value changed",
            listOf(SourceLocation(Revision.HEAD, "Example.java", SourceRange(2, 3), SourceRange(4, 7))),
        )
        val result = AnalysisResult(
            listOf(finding), listOf(Diagnostic(AnalysisStage.COMPARE, "unsupported", "Unresolved target")),
            AnalysisMetadata("base", "head", listOf("baseline"), 1, 0),
        )
        val json = jacksonObjectMapper().readTree(AnalysisReports.json(result))
        assertEquals("FAIL", json["verdict"].asText())
        assertEquals(4, json["findings"][0]["locations"][0]["column"]["start"].asInt())
        val text = AnalysisReports.text(result)
        assertTrue(text.contains("Proved 0/1 change obligations"))
        assertTrue(text.contains("HEAD Example.java:2:4-3:7"))
        assertTrue(text.contains("COMPARE/unsupported: Unresolved target"))
    }

    @Test
    fun `empty successful report contains verdict`() {
        val result = AnalysisResult(emptyList(), emptyList(), AnalysisMetadata("base", "head", emptyList(), 0, 0))
        assertEquals("GraphFold: PASS\nProved 0/0 change obligations", AnalysisReports.text(result))
    }
}
