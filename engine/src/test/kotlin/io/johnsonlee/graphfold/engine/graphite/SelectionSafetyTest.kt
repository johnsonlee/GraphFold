package io.johnsonlee.graphfold.engine.graphite

import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.ReturnNode
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.input.FoldSites
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Corrupt/ambiguous graph shapes are correctness inputs, never performance fixtures. */
class SelectionSafetyTest {
    private val caller = MethodDescriptor(TypeDescriptor("Owner"), "run", emptyList(), TypeDescriptor("void"))

    private fun site(id: Int, name: String, ordinal: Int?, origin: Int? = null, returns: String = "boolean") = CallSiteNode(
        NodeId(id), caller, MethodDescriptor(TypeDescriptor("Flags"), name, emptyList(), TypeDescriptor(returns)),
        null, null, emptyList(), ordinal, origin?.let(::NodeId),
    )

    private fun resolve(vararg sites: CallSiteNode): FoldSites {
        val builder = DefaultGraph.Builder()
        builder.addMethod(caller)
        sites.forEach { builder.addMethod(it.callee); builder.addNode(it) }
        val rule = FoldSites.parse(mapOf(
            "select" to "MATCH (cs:CallSite {callee_name: 'chosen'}) RETURN cs", "value" to false,
        ))
        return GraphiteAnalyzer().resolve(builder.build(), rule)
    }

    @Test fun `partial functional target selection is rejected`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            resolve(site(1, "get", 0), site(2, "chosen", -1, 1), site(3, "other", -1, 1))
        }
        assertTrue(failure.message.orEmpty().contains("only some targets"))
    }

    @Test fun `erased invocation with conflicting target result types is rejected`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            resolve(site(1, "get", 0, returns = "java.lang.Object"), site(2, "chosen", -1, 1),
                site(3, "chosen", -1, 1, "int"))
        }
        assertTrue(failure.message.orEmpty().contains("ambiguous erased"))
    }

    @Test fun `missing ordinals and invalid origins fail instead of choosing a nearby call`() {
        assertFailsWith<IllegalStateException> { resolve(site(1, "chosen", null)) }
        assertFailsWith<IllegalStateException> { resolve(site(1, "chosen", -1, 999)) }
    }

    @Test fun `cycles in origin chains are rejected`() {
        assertFailsWith<IllegalArgumentException> { resolve(site(1, "chosen", -1, 2), site(2, "other", -1, 1)) }
    }

    @Test fun `selecting all equivalent functional targets produces one bytecode replacement`() {
        val result = resolve(
            site(1, "get", 0, returns = "java.lang.Object"),
            site(2, "chosen", -1, 1), site(3, "chosen", -1, 1),
        )
        assertEquals(1, result.selected.orEmpty().size)
        assertEquals(listOf("boolean"), result.resultTypes.values.toList())
    }

    @Test fun `selecting the original invocation allows all its targets without narrowing result types`() {
        val result = resolve(site(1, "chosen", 0), site(2, "left", -1, 1), site(3, "right", -1, 1))
        assertEquals(1, result.selected.orEmpty().size)
        assertTrue(result.resultTypes.isEmpty())
    }

    @Test fun `cycle in descendants cannot turn a selected original invocation into a replacement`() {
        assertFailsWith<IllegalArgumentException> {
            resolve(site(1, "chosen", 0, 2), site(2, "other", -1, 1))
        }
    }

    @Test fun `derived creation without an invoke produces no replacement`() {
        assertTrue(resolve(site(1, "chosen", -1)).selected.orEmpty().isEmpty())
    }
    @Test fun `user query map projections cannot impersonate missing or unknown call nodes`() {
        val builder = DefaultGraph.Builder()
        builder.addMethod(caller)
        val call = site(1, "chosen", 0)
        builder.addMethod(call.callee)
        builder.addNode(call)
        builder.addNode(ReturnNode(NodeId(2), caller))
        val graph = builder.build()
        val cases = mapOf(
            "RETURN {type: 'CallSiteNode'} AS cs" to "without an id",
            "RETURN {type: 'CallSiteNode', id: 'invalid'} AS cs" to "without an id",
            "RETURN {type: 'CallSiteNode', id: 999999} AS cs" to "unknown CallSite",
            "RETURN {type: 'CallSiteNode', id: 2} AS cs" to "unknown CallSite",
        )
        cases.forEach { (query, reason) ->
            val rule = FoldSites.parse(mapOf("select" to query, "value" to false))
            val error = assertFailsWith<IllegalStateException> { GraphiteAnalyzer().resolve(graph, rule) }
            assertTrue(reason in error.message.orEmpty(), error.message)
        }
    }

}
