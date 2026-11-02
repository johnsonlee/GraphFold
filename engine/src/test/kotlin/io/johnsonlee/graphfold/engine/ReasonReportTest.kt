package io.johnsonlee.graphfold.engine

import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReasonReportTest {
    @Test
    fun `JSON preserves explanation categories proof code paths and exact ranges`() {
        val location = SourceLocation(Revision.HEAD, "src/Example.java", SourceRange(3, 3), SourceRange(31, 32))
        val reason = VerdictReason(setOf(ChangeKind.FIELD_INITIALIZER_CHANGED), ReasonCode.SURVIVING_DIFFERENCE,
            "Field initializer survives folding", listOf(location), "control", "old/Example.java", "src/Example.java")
        val result = AnalysisResult(
            listOf(Finding("change", FindingKind.UNVERIFIABLE_CHANGE, "fold", "Difference", listOf(location))),
            emptyList(), AnalysisMetadata("base", "head", listOf("control"), 1, 0), listOf(reason),
        )
        val mapper = jacksonObjectMapper()
        val json = mapper.readTree(AnalysisReports.json(result))
        assertEquals("FAIL", json["verdict"].asText())
        assertEquals(reason, mapper.treeToValue(json["reasons"].single(), VerdictReason::class.java))
        assertEquals(31, json["reasons"][0]["locations"][0]["column"]["start"].asInt())
        assertContains(AnalysisReports.text(result), "SURVIVING_DIFFERENCE [FIELD_INITIALIZER_CHANGED]: Field initializer survives folding")
    }

    @Test
    fun `passing unreachable explanation is retained without findings and optional paths default null`() {
        val reason = VerdictReason(setOf(ChangeKind.METHOD_ADDED), ReasonCode.UNREACHABLE, "No surviving call reaches the method",
            listOf(SourceLocation(Revision.HEAD, "Example.java", SourceRange(4, 4), SourceRange(1, 20))), "control")
        val result = AnalysisResult(emptyList(), emptyList(), AnalysisMetadata("base", "head", listOf("control"), 1, 1), listOf(reason))
        assertEquals(Verdict.PASS, result.verdict)
        assertContains(AnalysisReports.text(result), "UNREACHABLE [METHOD_ADDED]")
        val mapper = jacksonObjectMapper()
        val encoded = mapper.readTree(AnalysisReports.json(result))["reasons"][0] as ObjectNode
        encoded.remove("basePath")
        encoded.remove("headPath")
        val decoded = mapper.treeToValue(encoded, VerdictReason::class.java)
        assertEquals(reason, decoded)
        assertNull(decoded.basePath)
        assertNull(decoded.headPath)
    }

    @Test
    fun `legacy result JSON with no reasons still deserializes with empty explanations`() {
        val original = AnalysisResult(emptyList(), emptyList(), AnalysisMetadata("base", "head", emptyList(), 0, 0))
        val mapper = jacksonObjectMapper()
        val json = mapper.readTree(AnalysisReports.json(original)) as ObjectNode
        json.remove("reasons")
        json.remove("verdict")
        val decoded = mapper.treeToValue(json, AnalysisResult::class.java)
        assertTrue(decoded.reasons.isEmpty())
        assertEquals(Verdict.PASS, decoded.verdict)
    }
}
