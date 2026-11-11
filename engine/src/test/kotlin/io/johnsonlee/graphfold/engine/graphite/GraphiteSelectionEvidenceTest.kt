package io.johnsonlee.graphfold.engine.graphite

import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.graph.AnalysisCompleteness
import io.johnsonlee.graphite.graph.AnalysisStatus
import io.johnsonlee.graphite.graph.ArtifactEvidence
import io.johnsonlee.graphite.graph.ArtifactRole
import io.johnsonlee.graphite.graph.CallSiteGap
import io.johnsonlee.graphite.graph.ClassEvidence
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.graph.MethodEvidence
import io.johnsonlee.graphite.input.AnalysisIdentity
import io.johnsonlee.graphite.input.ConstantFold
import io.johnsonlee.graphite.input.ConstantPattern
import io.johnsonlee.graphite.input.FoldRule
import io.johnsonlee.graphite.input.FoldSites
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GraphiteSelectionEvidenceTest {
    private val caller = MethodDescriptor(TypeDescriptor("Application"), "run", emptyList(), TypeDescriptor("void"))
    private val callee = MethodDescriptor(TypeDescriptor("Flags"), "enabled", emptyList(), TypeDescriptor("boolean"))
    private val value = ConstantPattern("BooleanConstant", mapOf("value" to false))
    private val direct = FoldSites("MATCH (c:CallSite {callee_class: 'Flags'}) RETURN c", selected = null, value = value)
    private val structural = FoldSites(
        "MATCH (c:CallSite) OPTIONAL MATCH (v)-[:DATAFLOW]->(c) WITH c, count(v) AS n WHERE n = 0 RETURN c",
        selected = null, value = value,
    )
    private val evidence = AnalysisCompleteness(
        AnalysisIdentity(includePackages = listOf("Application")),
        mapOf("application.jar" to ArtifactEvidence(ArtifactRole.APPLICATION)),
        mapOf("Application" to ClassEvidence("application.jar", AnalysisStatus.ANALYZED)),
        emptyMap(),
    )

    @ParameterizedTest
    @ValueSource(strings = [
        "MATCH (c:CallSite) RETURN c",
        "MATCH (c:CallSite {callee_class: 'Flags', callee_name: 'enabled'}) RETURN c AS selected",
        "MATCH (c:CallSite) WHERE c.callee_signature = 'Flags.enabled()' AND c.ordinal = 0 RETURN DISTINCT c",
        "MATCH (c:CallSite) WHERE NOT c.caller_name = 'ignored' OR c.callee_class STARTS WITH 'Flags' RETURN c",
        "MATCH (c:CallSite) WHERE c.callee_name IN ['enabled', 'active'] RETURN c",
        "MATCH (c:CallSite) WHERE c.line IS NOT NULL AND c.callee_name =~ 'enabled.*' RETURN c",
    ])
    fun `certified queries inspect only intrinsic properties of the returned call`(query: String) {
        assertTrue(intrinsicCallSiteSelection(query), query)
    }

    @ParameterizedTest
    @ValueSource(strings = [
        "MATCH (c:CallSite) RETURN c LIMIT 1",
        "MATCH (c:CallSite) WITH c, count(*) AS n WHERE n = 1 RETURN c",
        "MATCH (c:CallSite) OPTIONAL MATCH (v)-[:DATAFLOW]->(c) WITH c, count(v) AS n WHERE n = 0 RETURN c",
        "MATCH (c:CallSite)<-[:DATAFLOW]-(v) RETURN c",
        "MATCH (c:CallSite), (other:CallSite) RETURN c",
        "MATCH path = (c:CallSite) RETURN c",
        "OPTIONAL MATCH (c:CallSite) RETURN c",
        "MATCH (c:CallSite) WHERE size(c.arguments) = 0 RETURN c",
        "MATCH (c:CallSite) WHERE c.arguments IS NULL RETURN c",
        "MATCH (c:CallSite) WHERE c.callee_name = toString(1) RETURN c",
        "MATCH (c:CallSite {arguments: null}) RETURN c",
        "MATCH (c:CallSite) RETURN c.callee_name",
        "MATCH (c:CallSite) RETURN c, c",
        "MATCH (c:CallSite:Other) RETURN c",
        "MATCH (c) RETURN c",
        "MATCH (c:CallSite) RETURN c UNION MATCH (d:CallSite) RETURN d",
        "not a Cypher query",
        "",
    ])
    fun `other shapes do not receive an intrinsic inventory exemption`(query: String) {
        assertFalse(intrinsicCallSiteSelection(query), query)
    }

    @Test fun `absence of selection completeness cannot be repaired by a later folded graph`() {
        val result = selectionEvidenceDiagnostics(DefaultGraph.Builder().build(), direct)
        assertTrue(result.any { "did not provide" in it })
    }

    @ParameterizedTest
    @EnumSource(AnalysisStatus::class, names = ["INCOMPLETE", "RESOLUTION_FAILED", "NO_BODY"])
    fun `extraction failures block direct and aggregate selection before folding`(status: AnalysisStatus) {
        val incomplete = evidence.copy(methods = mapOf(caller.identity to MethodEvidence(status, "original failure")))
        for (rule in listOf(direct, structural, constantRule())) {
            assertTrue(inspect(incomplete, rule).any { status.name in it && "original failure" in it })
        }
        assertTrue(inspect(evidence, structural).isEmpty(), "A later complete graph must not erase the earlier diagnostics")
    }

    @ParameterizedTest
    @EnumSource(AnalysisStatus::class, names = ["NATIVE", "ANALYZED_WITHOUT_CONTROL_FLOW"])
    fun `intrinsic selection does not inspect unrelated native bodies or branch scopes`(status: AnalysisStatus) {
        val incomplete = evidence.copy(methods = mapOf("UnusedLibrary.method()V" to MethodEvidence(status)))
        assertTrue(inspect(incomplete, direct).isEmpty())
        assertTrue(inspect(incomplete, constantRule()).isEmpty())
        assertTrue(inspect(incomplete, structural).any { status.name in it })
    }

    @Test fun `an abstract declaration has no missing body in either query mode`() {
        val declared = evidence.copy(methods = mapOf("Interface.method()V" to MethodEvidence(AnalysisStatus.ABSTRACT)))
        assertTrue(inspect(declared, direct).isEmpty())
        assertTrue(inspect(declared, structural).isEmpty())
    }

    @ParameterizedTest
    @EnumSource(CallSiteGap::class)
    fun `aggregate absence proofs require dispatch completeness but intrinsic calls remain selectable`(reason: CallSiteGap) {
        val incomplete = evidence.copy(callSiteGaps = mapOf(1 to reason))
        assertTrue(inspect(incomplete, direct).isEmpty())
        assertTrue(inspect(incomplete, structural).any { reason.name in it })
    }

    @ParameterizedTest
    @EnumSource(AnalysisStatus::class, names = ["EXCLUDED_BY_SCOPE", "NOT_EXPANDED"])
    fun `arbitrary graph predicates cannot infer absence from bodies left out of scope`(status: AnalysisStatus) {
        val scoped = evidence.copy(classes = evidence.classes + ("Dependency" to ClassEvidence("library.jar", status)))
        assertTrue(inspect(scoped, direct).isEmpty())
        assertTrue(inspect(scoped, structural).any { status.name in it })
    }

    @Test fun `class resolution failure remains blocking for every selector shape`() {
        val incomplete = evidence.copy(classes = evidence.classes +
            ("Broken" to ClassEvidence("broken.jar", AnalysisStatus.RESOLUTION_FAILED)))
        for (rule in listOf(direct, structural, constantRule())) {
            assertTrue(inspect(incomplete, rule).any { "Broken" in it && "RESOLUTION_FAILED" in it })
        }
    }

    private fun constantRule() = ConstantFold(
        callSite = mapOf("callee_class" to "Flags"),
        arguments = mapOf(0 to ConstantPattern("IntConstant", mapOf("value" to 12345))),
        value = value,
    )

    private fun inspect(completeness: AnalysisCompleteness, rule: FoldRule): List<String> {
        val builder = DefaultGraph.Builder()
        builder.addMethod(caller)
        builder.addNode(CallSiteNode(NodeId(1), caller, callee, null, null, emptyList(), 0, null))
        builder.setAnalysisCompleteness(completeness)
        return selectionEvidenceDiagnostics(builder.build(), rule)
    }
}
