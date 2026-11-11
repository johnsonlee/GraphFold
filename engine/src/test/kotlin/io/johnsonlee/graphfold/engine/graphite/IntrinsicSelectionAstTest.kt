package io.johnsonlee.graphfold.engine.graphite

import io.johnsonlee.graphite.cypher.CypherClause
import io.johnsonlee.graphite.cypher.CypherExpr
import io.johnsonlee.graphite.cypher.CypherPattern
import io.johnsonlee.graphite.cypher.PatternElement
import io.johnsonlee.graphite.cypher.ReturnItem
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The evidence exemption stays fail closed if upstream parsing admits additional AST shapes. */
class IntrinsicSelectionAstTest {
    private val variable = CypherExpr.Variable("site")
    private val name = CypherExpr.Property(variable, "callee_name")
    private val unknown = CypherExpr.Property(variable, "arguments")
    private val node = PatternElement.NodePattern("site", listOf("CallSite"))
    private val match = CypherClause.Match(listOf(CypherPattern(listOf(node))))
    private val projection = CypherClause.Return(listOf(ReturnItem(variable)))

    @Test fun `empty and structural clause sequences cannot masquerade as direct call inventory`() {
        val where = CypherClause.Where(CypherExpr.Literal(true))
        val with = CypherClause.With(listOf(ReturnItem(variable)))
        listOf(emptyList(), listOf(match), listOf(match, where, with, projection), listOf(where, projection),
            listOf(match, with), listOf(match, with, projection), listOf(match, CypherClause.Return(emptyList())),
            listOf(match, projection.copy(items = listOf(ReturnItem(variable), ReturnItem(name)))),
            listOf(match, projection.copy(items = listOf(ReturnItem(name)))))
            .forEach { assertFalse(intrinsicCallSiteSelection(it), it.toString()) }
        assertTrue(intrinsicCallSiteSelection(listOf(match, where, projection)))
    }

    @Test fun `every pattern must bind exactly one unqualified mandatory CallSite node`() {
        val invalid = listOf(
            match.copy(patterns = emptyList()), match.copy(patterns = match.patterns + match.patterns),
            match.copy(patterns = listOf(CypherPattern(emptyList()))),
            match.copy(patterns = listOf(CypherPattern(listOf(PatternElement.RelationshipPattern())))),
            match.copy(patterns = listOf(CypherPattern(listOf(node, node)))),
            match.copy(optional = true), match.copy(patterns = listOf(CypherPattern(listOf(node), "path"))),
            pattern(node.copy(variable = null)), pattern(node.copy(labels = listOf("Method"))),
            pattern(node.copy(properties = mapOf("arguments" to CypherExpr.Literal(null)))),
            pattern(node.copy(properties = mapOf("callee_name" to CypherExpr.FunctionCall("unknown", emptyList())))),
        )
        invalid.forEach { assertFalse(intrinsicCallSiteSelection(listOf(it, projection)), it.toString()) }
    }

    @Test fun `only scalar literal properties can establish intrinsic pattern selection`() {
        listOf(null, "enabled", 1, true, false).forEach { value ->
            assertTrue(intrinsicCallSiteSelection(listOf(
                pattern(node.copy(properties = mapOf("callee_name" to CypherExpr.Literal(value)))), projection)),
                value?.toString() ?: "<null literal>")
        }
        assertFalse(intrinsicCallSiteSelection(listOf(
            pattern(node.copy(properties = mapOf("callee_name" to CypherExpr.Literal(listOf("enabled"))))), projection)))
    }

    @Test fun `both operands of scalar predicates must stay within the selected call`() {
        val constructors = listOf<(CypherExpr, CypherExpr) -> CypherExpr>(
            { left, right -> CypherExpr.Comparison("=", left, right) },
            { left, right -> CypherExpr.StringOp("STARTS WITH", left, right) },
            { left, right -> CypherExpr.RegexMatch(left, right) },
        )
        constructors.forEach { predicate ->
            assertTrue(accepts(predicate(name, CypherExpr.Literal("enabled"))))
            assertFalse(accepts(predicate(unknown, CypherExpr.Literal("enabled"))))
            assertFalse(accepts(predicate(name, unknown)))
        }
        assertFalse(accepts(CypherExpr.Comparison("=", CypherExpr.Property(CypherExpr.Variable("other"), "callee_name"), name)))
        assertFalse(accepts(CypherExpr.Comparison("=", name, CypherExpr.Parameter("external"))))
        assertTrue(accepts(CypherExpr.IsNull(name)))
        assertTrue(accepts(CypherExpr.IsNotNull(name)))
        assertFalse(accepts(CypherExpr.IsNull(unknown)))
        assertFalse(accepts(CypherExpr.IsNotNull(unknown)))
    }

    @Test fun `list predicates require a literal list of scalar elements and an intrinsic left side`() {
        val literals = CypherExpr.ListLiteral(listOf(CypherExpr.Literal(null), CypherExpr.Literal("enabled")))
        assertTrue(accepts(CypherExpr.ListOp("IN", name, literals)))
        assertTrue(accepts(CypherExpr.ListOp("IN", name, CypherExpr.ListLiteral(emptyList()))))
        assertFalse(accepts(CypherExpr.ListOp("IN", unknown, literals)))
        assertFalse(accepts(CypherExpr.ListOp("IN", name, CypherExpr.Parameter("names"))))
        assertFalse(accepts(CypherExpr.ListOp("IN", name, CypherExpr.ListLiteral(listOf(unknown)))))
        assertTrue(accepts(CypherExpr.Literal(true)))
        assertTrue(accepts(CypherExpr.Literal(false)))
        assertFalse(accepts(CypherExpr.Literal("not a predicate")))
        assertFalse(accepts(CypherExpr.FunctionCall("exists", listOf(name))))
    }

    @Test fun `boolean combinations and both WHERE locations retain every operand's evidence requirement`() {
        val known = CypherExpr.IsNull(name)
        val unsafe = CypherExpr.IsNull(unknown)
        listOf<(CypherExpr, CypherExpr) -> CypherExpr>(
            { left, right -> CypherExpr.And(left, right) }, { left, right -> CypherExpr.Or(left, right) },
        ).forEach { combine ->
            assertTrue(accepts(combine(known, known)))
            assertFalse(accepts(combine(unsafe, known)))
            assertFalse(accepts(combine(known, unsafe)))
        }
        assertTrue(accepts(CypherExpr.Not(known)))
        assertFalse(accepts(CypherExpr.Not(unsafe)))
        assertTrue(intrinsicCallSiteSelection(listOf(match.copy(where = known), CypherClause.Where(known), projection)))
        assertFalse(intrinsicCallSiteSelection(listOf(match.copy(where = unsafe), CypherClause.Where(known), projection)))
        assertFalse(intrinsicCallSiteSelection(listOf(match.copy(where = known), CypherClause.Where(unsafe), projection)))
    }

    private fun pattern(node: PatternElement.NodePattern) = match.copy(patterns = listOf(CypherPattern(listOf(node))))
    private fun accepts(predicate: CypherExpr) = intrinsicCallSiteSelection(listOf(match.copy(where = predicate), projection))
}
