package io.johnsonlee.graphfold.engine

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VerdictSchemaTest {
    private val mapper = jacksonObjectMapper()
    private val schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
        .getSchema(AnalysisReports.schema())

    @Test
    fun `packaged schema identifies the versioned draft 2020-12 contract`() {
        val document = mapper.readTree(AnalysisReports.schema())
        assertEquals("https://json-schema.org/draft/2020-12/schema", document["\$schema"].asText())
        assertEquals("urn:io.johnsonlee.graphfold:verdict:1", document["\$id"].asText())
        assertEquals(1, report(passing())["schemaVersion"].asInt())
    }

    @Test
    fun `serialized passing and failing reports conform including findings diagnostics and reasons`() {
        val pass = passing()
        assertEquals("PASS", report(pass)["verdict"].asText())
        assertValid(pass)
        assertValid(pass.copy(diagnostics = listOf(
            Diagnostic(AnalysisStage.BUILD, DiagnosticCode.ANALYSIS_FAILED, "Nonblocking build failure", false),
        )))
        val fail = failing()
        assertEquals("FAIL", report(fail)["verdict"].asText())
        assertValid(fail)
        // A verdict may fail from incomplete obligations alone, without a finding or diagnostic.
        val incomplete = pass.copy(metadata = pass.metadata.copy(provedObligations = 0))
        assertEquals("FAIL", report(incomplete)["verdict"].asText())
        assertValid(incomplete)
    }

    @Test
    fun `every report enum exactly matches its schema values`() {
        val document = mapper.readTree(AnalysisReports.schema())
        val definitions = "/\$defs"
        val contracts = mapOf<String, List<Enum<*>>>(
            "/properties/verdict/enum" to Verdict.entries,
            "$definitions/revision/enum" to Revision.entries,
            "$definitions/finding/properties/kind/enum" to FindingKind.entries,
            "$definitions/diagnostic/properties/stage/enum" to AnalysisStage.entries,
            "$definitions/diagnostic/properties/code/enum" to DiagnosticCode.entries,
            "$definitions/reason/properties/changes/items/enum" to ChangeKind.entries,
            "$definitions/reason/properties/code/enum" to ReasonCode.entries,
            "$definitions/selector/properties/kind/enum" to RuleSelectorKind.entries,
            "$definitions/ruleAudit/properties/selectionStatus/enum" to RuleSelectionStatus.entries,
        )
        contracts.forEach { (path, values) ->
            assertEquals(values.map { it.name }.toSet(), document.at(path).map { it.asText() }.toSet(), path)
        }
    }

    @Test
    fun `every diagnostic code serializes by name and validates against the schema`() {
        val expectedCodes = DiagnosticCode.entries.map { it.name }.toSet()
        val result = passing().copy(diagnostics = DiagnosticCode.entries.map {
            Diagnostic(AnalysisStage.INPUT, it, "Diagnostic serialization contract")
        })
        assertEquals(expectedCodes, report(result)["diagnostics"].map { it["code"].asText() }.toSet())
        assertValid(result)
    }

    @Test
    fun `audit states distinguish unmeasured counts from selected zero and retained failure evidence`() {
        val selected = audit().copy(
            selectionStatus = RuleSelectionStatus.SELECTED,
            selectedNodes = listOf(site()),
            matchedCallCount = 4,
        )
        val audits = listOf(
            audit(),
            audit().copy(selectionStatus = RuleSelectionStatus.FAILED, message = "Unknown query variable"),
            selected.copy(selectedNodes = emptyList(), matchedCallCount = 0),
            selected,
            selected.copy(matchedCallCount = null, message = "Fold derivation failed after selection"),
            selected.copy(
                revision = Revision.BASE,
                selector = RuleSelector(RuleSelectorKind.CALL_SITE_MATCH, null, "match owner and method"),
                selectedNodes = listOf(site().copy(ordinal = null, originNodeId = null, lineNumber = null)),
            ),
        )
        val result = passing().copy(ruleAudits = audits)
        assertValid(result)
        val json = report(result)["ruleAudits"]
        assertTrue(json[0]["selectedCount"].isNull)
        assertTrue(json[1]["selectedCount"].isNull)
        assertEquals(0, json[2]["selectedCount"].asInt())
        assertEquals(1, json[3]["selectedCount"].asInt())
        assertEquals(4, json[3]["matchedCallCount"].asInt())
        assertTrue(json[4]["matchedCallCount"].isNull)
        assertEquals(1, json[4]["selectedNodes"].size())
    }

    @Test
    fun `schema rejects missing required fields at every report level`() {
        val original = report(failing())
        val requiredFields = listOf(
            "" to "schemaVersion", "" to "verdict", "" to "ruleAudits", "" to "findings",
            "" to "diagnostics", "" to "metadata", "" to "reasons",
            "/metadata" to "provedObligations", "/findings/0" to "locations",
            "/findings/0/evidence/0" to "location", "/findings/0/locations/0" to "revision",
            "/findings/0/locations/0/line" to "start", "/diagnostics/0" to "blocking",
            "/reasons/0" to "changes", "/ruleAudits/0" to "selectionStatus",
            "/ruleAudits/0" to "selectedCount", "/ruleAudits/0" to "matchedCallCount",
            "/ruleAudits/0/selector" to "query", "/ruleAudits/0/selectedNodes/0" to "nodeId",
            "/ruleAudits/0/selectedNodes/0/caller" to "descriptor",
        )
        requiredFields.forEach { (path, field) ->
            val modified = original.deepCopy()
            (modified.at(path) as ObjectNode).remove(field)
            assertInvalid(modified, "missing $path/$field")
        }
    }

    @Test
    fun `schema rejects invalid enums ranges types and nullable count states`() {
        val mutations = listOf(
            "/schemaVersion" to "2", "/schemaVersion" to "\"1\"", "/verdict" to "\"UNKNOWN\"",
            "/metadata/obligations" to "-1", "/metadata/provedObligations" to "1.5",
            "/findings/0/kind" to "\"OTHER\"", "/findings/0/id" to "\"  \"",
            "/findings/0/locations" to "[]", "/findings/0/locations/0/revision" to "\"WORKTREE\"",
            "/findings/0/locations/0/line/start" to "0", "/findings/0/locations/0/column/end" to "-1",
            "/diagnostics/0/blocking" to "\"true\"", "/diagnostics/0/stage" to "\"DEPLOY\"",
            "/diagnostics/0/code" to "\"UNKNOWN_DIAGNOSTIC\"",
            "/reasons/0/changes" to "[\"UNKNOWN\"]", "/reasons/0/code" to "\"OTHER\"",
            "/ruleAudits/0/ruleIndex" to "-1", "/ruleAudits/0/selectedCount" to "null",
            "/ruleAudits/0/selectedCount" to "-1", "/ruleAudits/0/matchedCallCount" to "-1",
            "/ruleAudits/0/selectionStatus" to "\"FAILED\"",
            "/ruleAudits/0/selectionStatus" to "\"NOT_EXECUTED\"",
            "/ruleAudits/0/selectionStatus" to "\"SKIPPED\"",
            "/ruleAudits/0/selector/kind" to "\"REGEX\"", "/ruleAudits/0/selector/query" to "3",
            "/ruleAudits/0/selectedNodes/0/nodeId" to "2147483648",
            "/ruleAudits/0/selectedNodes/0/ordinal" to "0.5",
            "/ruleAudits/0/selectedNodes/0/originNodeId" to "\"12\"",
            "/ruleAudits/0/selectedNodes/0/callee/descriptor" to "null",
            "/ruleAudits/0/selectedNodes" to "{}",
        )
        mutations.forEach { (path, json) ->
            val modified = report(failing())
            val parent = modified.at(path.substringBeforeLast('/')) as ObjectNode
            parent.set<JsonNode>(path.substringAfterLast('/'), mapper.readTree(json))
            assertInvalid(modified, "$path = $json")
        }
    }

    private fun passing() = AnalysisResult(
        emptyList(), emptyList(), AnalysisMetadata("base-sha", "head-sha", listOf("control"), 1, 1),
    )

    private fun failing(): AnalysisResult {
        val location = SourceLocation(Revision.HEAD, "src/Example.java", SourceRange(3, 4), SourceRange(12, 1))
        return passing().copy(
            findings = FindingKind.entries.map { kind ->
                Finding("finding-$kind", kind, "fold-0", "Changed call remains", listOf(location),
                    listOf(Evidence("Diff intersects call", location), Evidence("No equivalent fold")), listOf("control"))
            },
            diagnostics = AnalysisStage.entries.map {
                Diagnostic(it, DiagnosticCode.ANALYSIS_FAILED, "Analysis detail", it == AnalysisStage.FOLD)
            },
            reasons = ReasonCode.entries.map { code ->
                VerdictReason(ChangeKind.entries.toSet(), code, "Change classification", listOf(location), "control",
                    if (code == ReasonCode.UNREACHABLE) null else "src/Old.java", "src/Example.java")
            },
            ruleAudits = listOf(audit().copy(selectionStatus = RuleSelectionStatus.SELECTED,
                selectedNodes = listOf(site()), matchedCallCount = 1)),
        )
    }

    private fun audit() = RuleAudit("control", Revision.HEAD, 0,
        RuleSelector(RuleSelectorKind.CYPHER, "MATCH (call:CallSite) RETURN call", "all calls"))

    private fun site() = SelectedCallSite(-2,
        SelectedMethod("example.Caller.run()", "()V"), SelectedMethod("example.Target.value()", "()I"),
        ordinal = -1, originNodeId = 0, lineNumber = 0)

    private fun report(result: AnalysisResult): ObjectNode = mapper.readTree(AnalysisReports.json(result)) as ObjectNode

    private fun assertValid(result: AnalysisResult) {
        val errors = schema.validate(report(result))
        assertTrue(errors.isEmpty(), errors.joinToString("\n"))
    }

    private fun assertInvalid(json: JsonNode, description: String) {
        assertTrue(schema.validate(json).isNotEmpty(), "Schema accepted $description")
    }
}
