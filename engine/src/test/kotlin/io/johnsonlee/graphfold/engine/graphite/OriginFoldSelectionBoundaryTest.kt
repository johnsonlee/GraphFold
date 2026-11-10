package io.johnsonlee.graphfold.engine.graphite

import io.johnsonlee.graphfold.engine.RuleSelectionStatus
import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.input.ConstantFold
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class OriginFoldSelectionBoundaryTest {
    private val caller = MethodDescriptor(TypeDescriptor("Owner"), "run", emptyList(), TypeDescriptor("void"))
    private val callee = MethodDescriptor(TypeDescriptor("Flags"), "chosen", emptyList(), TypeDescriptor("boolean"))
    private val rule = ConstantFold.parse(mapOf("match" to mapOf("CallSite" to mapOf("callee_name" to "chosen")), "value" to false))

    @Test fun `constant selectors never construct source keys for absent or derived ordinals`() {
        val sites = listOf(null, -1, 0).mapIndexed { index, ordinal ->
            CallSiteNode(NodeId(index + 1), caller, callee, 3, null, emptyList(), ordinal)
        }
        val graph = DefaultGraph.Builder().apply {
            addMethod(caller)
            addMethod(callee)
            sites.forEach(::addNode)
        }.build()
        assertEquals(listOf(sites.last()), constantSelection(graph, rule))
        val selected = originFoldSelection(graph, listOf(rule), emptyMap(),
            { _, _, _ -> error("A constant rule does not use the Cypher resolver") }, RuleAuditCapture { _, _ -> })
        assertEquals(setOf(0), selected.single().selected.orEmpty().map { it.ordinal }.toSet())
    }

    @Test fun `failed original rule validation is audited before any normalized selection can be trusted`() {
        val updates = mutableMapOf<Int, RuleAuditUpdate>()
        val audit = RuleAuditCapture { index, update -> updates[index] = update }
        val failure = IllegalArgumentException("Original graph identity is incomplete")
        val thrown = assertFailsWith<IllegalArgumentException> {
            originFoldSelection(DefaultGraph.Builder().build(), listOf(rule), emptyMap(),
                { _, _, _ -> error("Resolver must not run after failed validation") }, audit, validate = { throw failure })
        }
        assertSame(failure, thrown)
        assertEquals(RuleSelectionStatus.FAILED, updates.getValue(0).selectionStatus)
        assertEquals(failure.message, updates.getValue(0).message)
    }
}
