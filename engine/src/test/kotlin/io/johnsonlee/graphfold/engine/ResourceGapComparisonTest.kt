package io.johnsonlee.graphfold.engine


import io.johnsonlee.graphfold.engine.inputs.ContentInventory
import io.johnsonlee.graphfold.engine.graphite.GraphiteCompleteness
import io.johnsonlee.graphfold.engine.snapshot.MethodAnalysisGap
import io.johnsonlee.graphfold.engine.snapshot.MethodSnapshot
import io.johnsonlee.graphfold.engine.snapshot.ProgramSnapshot
import io.johnsonlee.graphfold.engine.snapshot.ResourceRead
import io.johnsonlee.graphfold.engine.source.ProofObligation
import io.johnsonlee.graphfold.engine.source.SymbolKind
import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.graph.AnalysisCompleteness
import io.johnsonlee.graphite.graph.AnalysisStatus
import io.johnsonlee.graphite.graph.CallSiteGap
import io.johnsonlee.graphite.graph.ClassEvidence
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.graph.MethodEvidence
import io.johnsonlee.graphite.input.AnalysisIdentity
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import sootup.core.model.MethodModifier
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ResourceGapComparisonTest {
    private val caller = MethodDescriptor(TypeDescriptor("Application"), "run", emptyList(), TypeDescriptor("void"))
    private val resource = MethodDescriptor(TypeDescriptor("java.util.ResourceBundle"), "getString",
        listOf(TypeDescriptor("java.lang.String")), TypeDescriptor("java.lang.String"))
    private val callback = MethodDescriptor(TypeDescriptor("Callback"), "read", emptyList(), TypeDescriptor("void"))

    @Test fun `a modeled resource dispatch remains a structural gap for whole program and source obligations`() {
        val snapshot = snapshot(completeness())
        val comparison = comparisonWithUncomparedChange(snapshot)
        val structural = comparison.analysisGaps()
        assertEquals(1, structural.size)
        assertTrue(resource.identity in structural.single())
        assertEquals(structural, comparison.analysisGaps(ProofObligation(caller.identity, SymbolKind.METHOD,
            "Application", emptyList())))
        assertTrue(comparison.resourceAnalysisGaps().isEmpty())
    }

    @ParameterizedTest
    @EnumSource(AnalysisStatus::class, names = [
        "NATIVE", "NO_BODY", "INCOMPLETE", "RESOLUTION_FAILED", "ANALYZED_WITHOUT_CONTROL_FLOW",
    ])
    fun `a modeled call cannot discharge an independent body failure in the same method`(status: AnalysisStatus) {
        val snapshot = snapshot(completeness(status))
        val comparison = comparisonWithUncomparedChange(snapshot)
        assertEquals(2, comparison.analysisGaps().size)
        val resourceGaps = comparison.resourceAnalysisGaps()
        assertEquals(1, resourceGaps.size)
        assertTrue(status.name in resourceGaps.single())
        assertFalse(snapshot.methodDiagnostics.getValue(caller.identity).first { status.name in it.message }.resourceEffectModeled)
    }

    @Test fun `a second unmodeled dispatch in the same method still blocks the resource proof`() {
        val snapshot = snapshot(completeness(unmodeledCallback = true))
        val comparison = comparisonWithUncomparedChange(snapshot)
        assertEquals(2, comparison.analysisGaps().size)
        val resourceGaps = comparison.resourceAnalysisGaps()
        assertEquals(1, resourceGaps.size)
        assertTrue(callback.identity in resourceGaps.single())
    }

    @Test fun `inventory and recovery gaps remain beside a modeled dispatch`() {
        val captured = snapshot(completeness())
        val extra = listOf(MethodAnalysisGap("inventory could not resolve field owner"),
            MethodAnalysisGap("frontend recovered without requested transforms"))
        val snapshot = captured.copy(methodDiagnostics = mapOf(caller.identity to
            (captured.methodDiagnostics.getValue(caller.identity) + extra)))
        val comparison = comparisonWithUncomparedChange(snapshot)
        assertEquals(3, comparison.analysisGaps().size)
        assertEquals(extra.map { it.message }.sorted(), comparison.resourceAnalysisGaps())
    }

    @Test fun `mapping uncertainty retains real graph gaps without inventing resource failures`() {
        val snapshot = snapshot(completeness())
        val comparison = SnapshotComparison(snapshot, snapshot, originsVerified = false)
        assertEquals(1, comparison.analysisGaps().size)
        assertTrue(comparison.resourceAnalysisGaps().isEmpty())
    }

    @Test fun `a head resource model cannot excuse an unmodeled base dispatch`() {
        val base = snapshot(completeness(modelResource = false))
        val head = snapshot(completeness())
        val comparison = SnapshotComparison(base, head)
        assertEquals(1, comparison.resourceAnalysisGaps().size)
        assertTrue(resource.identity in comparison.resourceAnalysisGaps().single())
    }

    @Test fun `resource dispatch exemption does not hide changed folded bytecode`() {
        val base = snapshot(completeness())
        val method = base.methods.getValue(caller.identity)
        val head = base.copy(methods = mapOf(caller.identity to method.copy(canonical = "changed", referenceCanonical = "changed")))
        val comparison = SnapshotComparison(base, head)
        assertTrue(comparison.resourceAnalysisGaps().isEmpty())
        assertEquals(listOf(caller.identity), comparison.wholeProgramDifferences())
    }

    @Test fun `modeled dispatch cannot turn a dynamic resource key into an unread key proof`() {
        val captured = snapshot(completeness())
        val method = captured.methods.getValue(caller.identity)
        val dynamicRead = method.copy(resourceReads = listOf(ResourceRead("messages", null)))
        val snapshot = captured.copy(methods = mapOf(caller.identity to dynamicRead))
        val comparison = comparisonWithUncomparedChange(snapshot)
        val proof = InputChangeProof(emptyList(), inventory("before"), inventory("after"))
        assertTrue(comparison.resourceAnalysisGaps().isEmpty())
        assertFalse(proof.resourcesUnreachable(comparison.reachableSnapshots(), comparison.resourceDependenciesAnalyzed))
    }

    private fun comparisonWithUncomparedChange(snapshot: ProgramSnapshot): SnapshotComparison {
        val identity = "Application.unseen()V"
        val before = MethodSnapshot(identity, "before", emptySet(), setOf(MethodModifier.PRIVATE), emptySet())
        val after = before.copy(canonical = "after", referenceCanonical = "after")
        return SnapshotComparison(snapshot.copy(methods = snapshot.methods + (identity to before)),
            snapshot.copy(methods = snapshot.methods + (identity to after)))
    }

    private fun completeness(
        status: AnalysisStatus? = null, unmodeledCallback: Boolean = false, modelResource: Boolean = true,
    ): GraphiteCompleteness {
        val builder = DefaultGraph.Builder()
        builder.addMethod(caller)
        builder.addNode(CallSiteNode(NodeId(1), caller, resource, null, null, emptyList(), 0, null))
        builder.addNode(CallSiteNode(NodeId(2), caller, callback, null, null, emptyList(), 0, null))
        builder.setAnalysisCompleteness(AnalysisCompleteness(
            AnalysisIdentity(), emptyMap(), mapOf("Application" to ClassEvidence(null, AnalysisStatus.ANALYZED)),
            status?.let { mapOf(caller.identity to MethodEvidence(it)) }.orEmpty(),
            callSiteGaps = mapOf(1 to CallSiteGap.DISPATCH_UNRESOLVED) +
                if (unmodeledCallback) mapOf(2 to CallSiteGap.DISPATCH_PARTIAL) else emptyMap(),
        ))
        return GraphiteCompleteness(builder.build(), modeledResourceCall = { modelResource && it.callee == resource })
    }

    private fun snapshot(completeness: GraphiteCompleteness): ProgramSnapshot {
        val method = MethodSnapshot(caller.identity, "body", setOf(resource.identity, callback.identity),
            setOf(MethodModifier.PUBLIC), emptySet())
        return ProgramSnapshot(mapOf(caller.identity to method), mapOf("Application" to "public class"),
            completeness.diagnostics, emptyList(), entryClasses = setOf("Application"),
            methodDiagnostics = completeness.methodDiagnostics, resourceDependenciesAnalyzed = true)
    }

    private fun inventory(value: String): ContentInventory {
        val entries = mapOf("resource/0/messages.properties" to value)
        return ContentInventory(entries, entries, resourceEntries = mapOf("messages.properties" to mapOf("changed" to value)),
            resourceIndependentEntries = mapOf("resource/0/messages.properties" to "properties:messages.properties"),
            resourceConsumersKnown = true)
    }
}
