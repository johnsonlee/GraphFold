package io.johnsonlee.graphfold.engine

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.assertIs

class ChangeAssessmentReportTest {
    private val mapper = jacksonObjectMapper()
    private val schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
        .getSchema(AnalysisReports.schema())

    @Test
    fun `compatibility assessments do not relabel equivalent or identical inputs as controlled`() {
        val expected = mapOf(
            ReasonCode.UNREACHABLE to ControlClassification.CONTROLLED,
            ReasonCode.FOLDED_EQUIVALENT to ControlClassification.UNPROVEN,
            ReasonCode.BUILD_OUTPUT_UNCHANGED to ControlClassification.UNCONTROLLED,
            ReasonCode.SURVIVING_DIFFERENCE to ControlClassification.UNCONTROLLED,
            ReasonCode.ANALYSIS_INCOMPLETE to ControlClassification.UNPROVEN,
            ReasonCode.STATIC_SAFETY_PROVEN to ControlClassification.UNPROVEN,
        )
        expected.forEach { (code, control) ->
            val reason = reason(code)
            assertEquals(control, reason.assessment.control)
            assertTrue(reason.assessment.safety.isEmpty())
            assertValid(result(reason))
        }
        assertEquals(Verdict.FAIL, result(reason(ReasonCode.STATIC_SAFETY_PROVEN)).verdict)
        assertEquals(AcceptanceDecision.REJECTED, reason(ReasonCode.STATIC_SAFETY_PROVEN).assessment.decision)
        assertEquals(AcceptanceBasis.INCOMPLETE_PROOF, reason(ReasonCode.STATIC_SAFETY_PROVEN).assessment.basis)
    }

    @Test
    fun `legacy reason decoding uses conservative defaults without inventing safety evidence`() {
        val original = reason(ReasonCode.FOLDED_EQUIVALENT)
        val json = mapper.valueToTree<ObjectNode>(original)
        json.remove("assessment")
        assertEquals(original, mapper.treeToValue(json, VerdictReason::class.java))
    }

    @Test
    fun `static acceptance retains uncontrolled classification and cites whole change certificate`() {
        val assessment = accepted()
        val report = result(reason(ReasonCode.STATIC_SAFETY_PROVEN).copy(assessment = assessment))
        assertValid(report)
        assertEquals(Verdict.PASS, report.verdict)
        val json = mapper.readTree(AnalysisReports.json(report))["reasons"][0]["assessment"]
        assertEquals("UNCONTROLLED", json["control"].asText())
        assertEquals("ACCEPTED", json["decision"].asText())
        assertEquals("WHOLE_CHANGE_EQUIVALENT", json["safety"][0]["claim"].asText())
        val text = AnalysisReports.text(report)
        assertTrue(text.contains("UNCONTROLLED: ACCEPTED via STATIC_SAFETY"))
        assertTrue(text.contains("WHOLE_CHANGE_EQUIVALENT PROVEN: Example.value()I"))
        assertTrue(text.contains("evidence: Both revisions return the identical constant"))
        assertTrue(text.contains("assumption: Complete declared static inputs"))
    }

    @Test
    fun `method safety alone does not accept changed return value or fabricate whole change proof`() {
        val properties = SafetyClaim.entries.filter { it != SafetyClaim.WHOLE_CHANGE_EQUIVALENT }
            .map { proof().copy(claim = it) }
        assertFailsWith<IllegalArgumentException> { accepted(properties) }
        val incomplete = ChangeAssessment(ControlClassification.UNCONTROLLED, properties,
            AcceptanceDecision.REJECTED, AcceptanceBasis.INCOMPLETE_PROOF)
        assertValid(result(reason(ReasonCode.ANALYSIS_INCOMPLETE).copy(assessment = incomplete)))
        assertFailsWith<IllegalArgumentException> {
            reason(ReasonCode.STATIC_SAFETY_PROVEN).copy(assessment =
                reason(ReasonCode.FOLDED_EQUIVALENT).assessment)
        }
        assertFailsWith<IllegalArgumentException> { accepted(emptyList()) }
        assertFailsWith<IllegalArgumentException> { accepted().copy(control = ControlClassification.CONTROLLED) }
        assertFailsWith<IllegalArgumentException> { accepted().copy(control = ControlClassification.UNPROVEN) }
    }

    @Test
    fun `missing and refuted evidence cannot masquerade as proven static acceptance`() {
        val invalid = listOf(
            proof().copy(status = SafetyStatus.UNPROVEN), proof().copy(status = SafetyStatus.REFUTED),
            proof().copy(subject = " "), proof().copy(evidence = emptyList()),
            proof().copy(evidence = listOf(" ")),
        )
        invalid.forEach { evidence -> assertFailsWith<IllegalArgumentException> { accepted(listOf(evidence)) } }
        SafetyStatus.entries.forEach { status ->
            val assessment = ChangeAssessment(ControlClassification.UNPROVEN, listOf(proof().copy(status = status)),
                AcceptanceDecision.REJECTED, AcceptanceBasis.STATIC_SAFETY)
            assertValid(result(reason(ReasonCode.ANALYSIS_INCOMPLETE).copy(assessment = assessment)))
            assertTrue(AnalysisReports.text(result(reason(ReasonCode.ANALYSIS_INCOMPLETE)
                .copy(assessment = assessment))).contains("WHOLE_CHANGE_EQUIVALENT $status"))
        }
    }

    @Test
    fun `schema independently rejects empty certificates unknown axes and missing evidence fields`() {
        val original = mapper.readTree(AnalysisReports.json(result(reason(ReasonCode.STATIC_SAFETY_PROVEN)
            .copy(assessment = accepted()))))
        val mutations = mapOf(
            "/control" to "\"CONTROLLED\"", "/decision" to "\"UNKNOWN\"", "/basis" to "\"REPRESENTATION_EQUIVALENCE\"",
            "/safety" to "[]", "/safety/0/claim" to "\"EFFECT_FREE\"",
            "/safety/0/status" to "\"UNPROVEN\"", "/safety/0/evidence" to "[]",
            "/safety/0/subject" to "\" \"", "/safety/0/assumptions" to "[\" \u0020\"]",
        )
        mutations.forEach { (path, value) ->
            val copy = original.deepCopy<JsonNode>()
            val full = "/reasons/0/assessment$path"
            assertIs<ObjectNode>(copy.at(full.substringBeforeLast('/')))
                .set<JsonNode>(full.substringAfterLast('/'), mapper.readTree(value))
            assertTrue(schema.validate(copy).isNotEmpty(), path)
        }
        listOf("claim", "status", "subject", "evidence", "assumptions").forEach { field ->
            val copy = original.deepCopy<JsonNode>()
            assertIs<ObjectNode>(copy.at("/reasons/0/assessment/safety/0")).remove(field)
            assertTrue(schema.validate(copy).isNotEmpty(), field)
        }
    }

    private fun proof() = SafetyEvidence(SafetyClaim.WHOLE_CHANGE_EQUIVALENT, SafetyStatus.PROVEN,
        "Example.value()I", listOf("Both revisions return the identical constant"),
        listOf("Complete declared static inputs"))

    private fun accepted(evidence: List<SafetyEvidence> = listOf(proof())) = ChangeAssessment(
        ControlClassification.UNCONTROLLED, evidence, AcceptanceDecision.ACCEPTED, AcceptanceBasis.STATIC_SAFETY,
    )

    private fun reason(code: ReasonCode) = VerdictReason(emptySet(), code, "Static comparison", emptyList(), "baseline")

    private fun result(reason: VerdictReason) = AnalysisResult(emptyList(), emptyList(),
        AnalysisMetadata("base", "head", listOf("baseline"), 1, 1), listOf(reason))

    private fun assertValid(result: AnalysisResult) {
        val errors = schema.validate(mapper.readTree(AnalysisReports.json(result)))
        assertTrue(errors.isEmpty(), errors.joinToString("\n"))
    }
}
