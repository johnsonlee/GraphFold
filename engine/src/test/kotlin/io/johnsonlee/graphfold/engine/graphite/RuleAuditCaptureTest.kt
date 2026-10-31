package io.johnsonlee.graphfold.engine.graphite

import io.johnsonlee.graphfold.engine.RuleSelectionStatus
import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.input.FoldSites
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RuleAuditCaptureTest {
    private val caller = MethodDescriptor(TypeDescriptor("Owner"), "run", emptyList(), TypeDescriptor("void"))
    private fun site(id: Int, ordinal: Int?, origin: Int? = null) = CallSiteNode(NodeId(id), caller,
        MethodDescriptor(TypeDescriptor("Flags"), "chosen", emptyList(), TypeDescriptor("boolean")),
        3, null, emptyList(), ordinal, origin?.let(::NodeId))

    @Test
    fun `raw derived nodes are recorded before folding combines their origin keys`() {
        val origin = site(1, 0)
        val left = site(2, -1, 1)
        val right = site(3, -1, 1)
        val graph = DefaultGraph.Builder().apply {
            addMethod(caller)
            listOf(origin, left, right).forEach { addMethod(it.callee); addNode(it) }
        }.build()
        val records = mutableMapOf<Int, RuleAuditUpdate>()
        val audit = RuleAuditCapture { index, update -> records[index] = update }
        val rule = FoldSites.parse(mapOf("select" to "MATCH (cs:CallSite) WHERE cs.ordinal < 0 RETURN cs", "value" to false))
        val resolved = GraphiteAnalyzer().resolve(graph, rule) { audit.selected(0, it) }
        assertEquals(1, resolved.selected.orEmpty().size)
        assertEquals(listOf(2, 3), records.getValue(0).selectedNodes.map { it.nodeId })
        assertTrue(records.getValue(0).selectedNodes.all { it.originNodeId == 1 && it.ordinal == -1 && it.lineNumber == 3 })
        audit.applied(0, 1)
        assertEquals(1, records.getValue(0).matchedCallCount)
        audit.failed(0, IllegalStateException("later fold failure"))
        assertEquals(RuleSelectionStatus.SELECTED, records.getValue(0).selectionStatus)
        assertEquals("later fold failure", records.getValue(0).message)
    }

    @Test
    fun `selection deduplicates query rows and failed queries never claim zero successful matches`() {
        val records = mutableMapOf<Int, RuleAuditUpdate>()
        val audit = RuleAuditCapture { index, update -> records[index] = update }
        val node = site(1, null)
        audit.selected(0, listOf(node, node))
        assertEquals(1, records.getValue(0).selectedNodes.size)
        assertEquals(null, records.getValue(0).selectedNodes.single().originNodeId)
        audit.failed(1, IllegalArgumentException())
        assertEquals(RuleSelectionStatus.FAILED, records.getValue(1).selectionStatus)
        assertEquals(null, records.getValue(1).message)
        assertFailsWith<NoSuchElementException> { audit.applied(2, 1) }
    }
}
