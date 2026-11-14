package io.johnsonlee.graphfold.engine.graphite

import io.johnsonlee.graphfold.engine.FoldReachability

import io.johnsonlee.graphfold.engine.snapshot.MethodSnapshot
import io.johnsonlee.graphfold.engine.snapshot.ProgramSnapshot

import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.graph.AnalysisCompleteness
import io.johnsonlee.graphite.graph.AnalysisStatus
import io.johnsonlee.graphite.graph.ArtifactEvidence
import io.johnsonlee.graphite.graph.ArtifactRole
import io.johnsonlee.graphite.graph.ClassEvidence
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.graph.MethodEvidence
import io.johnsonlee.graphite.input.AnalysisIdentity
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import sootup.core.model.MethodModifier
import kotlin.test.assertEquals

class FoldReachabilityTest {
    @ParameterizedTest
    @ValueSource(strings = ["direct", "derived", "missing-ordinal", "negative-ordinal", "global-gap",
        "method-gap", "excluded-root", "unspecified-roots"])
    fun `same-input removal requires a complete direct edge from an admitted root`(mode: String) {
        val caller = descriptor("App", "run")
        val callee = descriptor("Hidden", "work")
        val edge = CallSiteNode(NodeId(1), caller, callee, null, null, emptyList(), 0)
        val actual = when (mode) {
            "derived" -> edge.copy(origin = NodeId(2))
            "missing-ordinal" -> edge.copy(ordinal = null)
            "negative-ordinal" -> edge.copy(ordinal = -1)
            else -> edge
        }
        val graph = DefaultGraph.Builder().apply {
            addMethod(caller); addMethod(callee); addNode(actual)
            if (mode != "global-gap") setAnalysisCompleteness(AnalysisCompleteness(
                AnalysisIdentity(includePackages = listOf("App", "Hidden")),
                mapOf("prepared" to ArtifactEvidence(ArtifactRole.APPLICATION)),
                mapOf("App" to ClassEvidence("prepared", AnalysisStatus.ANALYZED),
                    "Hidden" to ClassEvidence("prepared", AnalysisStatus.ANALYZED)),
                if (mode == "method-gap") mapOf(caller.identity to MethodEvidence(AnalysisStatus.NO_BODY)) else emptyMap(),
            ))
        }.build()
        val root = method(caller.identity).copy(references = listOf("State.value:I", caller.identity))
        val hidden = method(callee.identity, isPrivate = true)
        val initializer = method("State.<clinit>()V")
        val methods = listOf(root, hidden, initializer).associateBy { it.identity }
        val folded = ProgramSnapshot(methods, emptyMap(), emptyList(), listOf(1),
            entryClasses = when (mode) {
                "excluded-root" -> emptySet()
                "unspecified-roots" -> null
                else -> setOf("App")
            })
        val proof = FoldReachability()
        captureReachability(proof, graph, GraphiteCompleteness(graph))
        assertEquals(if (mode in setOf("direct", "unspecified-roots")) setOf(hidden.identity) else emptySet(), proof.witnessed(folded))
    }

    private fun descriptor(owner: String, name: String) =
        MethodDescriptor(TypeDescriptor(owner), name, emptyList(), TypeDescriptor("void"))

    private fun method(identity: String, isPrivate: Boolean = false) = MethodSnapshot(identity, "body", emptySet(),
        setOf(if (isPrivate) MethodModifier.PRIVATE else MethodModifier.PUBLIC), emptySet())
}
