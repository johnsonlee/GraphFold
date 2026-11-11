package io.johnsonlee.graphfold.engine.graphite

import io.johnsonlee.graphite.cypher.CypherClause
import io.johnsonlee.graphite.cypher.CypherDslAdapter
import io.johnsonlee.graphite.cypher.CypherExpr
import io.johnsonlee.graphite.cypher.PatternElement
import io.johnsonlee.graphite.graph.AnalysisStatus
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.input.ConstantFold
import io.johnsonlee.graphite.input.FoldRule
import io.johnsonlee.graphite.input.FoldSites

/**
 * Validates evidence before selecting fold targets. A later folded graph cannot prove that an
 * earlier query was evaluated on complete facts: folding may remove the very gap that affected
 * an aggregate, a negative predicate, or a path match.
 *
 * Intrinsic CallSite predicates depend only on the bytecode call inventory. They do not require
 * branch scopes, native implementations, or resolved callback targets. Resolution of a selected
 * derived target to a bytecode call still requires the separate dispatch check in the analyzer.
 * ConstantFold matches these same intrinsic properties; its argument constraints are checked
 * against the body by the fold pass, not by its approximate Cypher preview.
 *
 * Other query shapes conservatively require complete graph structure throughout the declared
 * scope. This is deliberately stricter than a future query-specific dependency analysis.
 */
internal fun selectionEvidenceDiagnostics(
    graph: Graph,
    rule: FoldRule,
    completeness: GraphiteCompleteness = GraphiteCompleteness(graph),
): List<String> {
    val failures = completeness.diagnostics.toMutableList()
    val evidence = graph.analysisCompleteness() ?: return failures
    val intrinsic = when (rule) {
        is ConstantFold -> true
        is FoldSites -> intrinsicCallSiteSelection(rule.select)
    }
    evidence.methods.toSortedMap().forEach { (identity, method) ->
        val unavailable = method.status in SELECTION_EXTRACTION_FAILURES ||
            (!intrinsic && method.status in SELECTION_STRUCTURE_GAPS)
        if (unavailable) {
            failures.add("Graphite fold selection requires unavailable ${method.status} evidence: $identity" +
                method.message?.let { ": $it" }.orEmpty())
        }
    }
    if (!intrinsic) {
        evidence.classes.toSortedMap().filterValues { it.status in SELECTION_SCOPE_GAPS }.forEach { (owner, declaration) ->
            failures.add("Graphite fold selection requires unavailable ${declaration.status} evidence: class $owner")
        }
        evidence.callSiteGaps.toSortedMap().forEach { (id, reason) ->
            failures.add("Graphite fold selection requires complete dispatch: $reason at call site $id")
        }
    }
    return failures.distinct()
}

/** Certifies a small, node-local query language using Graphite's actual parsed AST. */
internal fun intrinsicCallSiteSelection(query: String): Boolean = runCatching {
    intrinsicCallSiteSelection(CypherDslAdapter.parse(query))
}.getOrDefault(false)

/** Validates the parsed selector independently of the parser's current normalization choices. */
@Suppress("ReturnCount") // Each rejected AST shape is an independent fail-closed certification guard.
internal fun intrinsicCallSiteSelection(clauses: List<CypherClause>): Boolean {
    if (clauses.size !in 2..CLAUSES_WITH_SEPARATE_WHERE) return false
    val match = clauses.first() as? CypherClause.Match ?: return false
    val projection = clauses.last() as? CypherClause.Return ?: return false
    val variable = intrinsicMatchVariable(match) ?: return false
    if (projection.items.size != 1 || projection.items.single().expression != CypherExpr.Variable(variable)) {
        return false
    }
    val predicate = if (clauses.size == CLAUSES_WITH_SEPARATE_WHERE) {
        val where = clauses[1] as? CypherClause.Where ?: return false
        where.condition
    } else null
    return listOfNotNull(match.where, predicate).all { intrinsicPredicate(it, variable) }
}

private fun intrinsicMatchVariable(match: CypherClause.Match): String? {
    val pattern = match.patterns.singleOrNull()
    val node = pattern?.elements?.singleOrNull() as? PatternElement.NodePattern
    val intrinsic = node?.takeIf { it.labels == listOf("CallSite") &&
        it.properties.all { (name, value) -> name in INTRINSIC_CALL_SITE_PROPERTIES && scalarLiteral(value) } }
    return if (match.optional || pattern?.pathVariable != null) null else intrinsic?.variable
}

private fun intrinsicPredicate(expression: CypherExpr, variable: String): Boolean = when (expression) {
    is CypherExpr.And -> intrinsicPredicate(expression.left, variable) && intrinsicPredicate(expression.right, variable)
    is CypherExpr.Or -> intrinsicPredicate(expression.left, variable) && intrinsicPredicate(expression.right, variable)
    is CypherExpr.Not -> intrinsicPredicate(expression.expression, variable)
    else -> intrinsicScalarPredicate(expression, variable)
}

private fun intrinsicScalarPredicate(expression: CypherExpr, variable: String): Boolean = when (expression) {
    is CypherExpr.Comparison -> intrinsicScalar(expression.left, variable) && intrinsicScalar(expression.right, variable)
    is CypherExpr.StringOp -> intrinsicScalar(expression.left, variable) && intrinsicScalar(expression.right, variable)
    is CypherExpr.RegexMatch -> intrinsicScalar(expression.left, variable) && intrinsicScalar(expression.right, variable)
    is CypherExpr.IsNull -> intrinsicScalar(expression.expression, variable)
    is CypherExpr.IsNotNull -> intrinsicScalar(expression.expression, variable)
    is CypherExpr.ListOp -> intrinsicScalar(expression.left, variable) &&
        (expression.right as? CypherExpr.ListLiteral)?.elements?.all(::scalarLiteral) == true
    is CypherExpr.Literal -> expression.value is Boolean
    else -> false
}

private fun intrinsicScalar(expression: CypherExpr, variable: String): Boolean = scalarLiteral(expression) ||
    (expression is CypherExpr.Property && expression.expression == CypherExpr.Variable(variable) &&
        expression.propertyName in INTRINSIC_CALL_SITE_PROPERTIES)

private fun scalarLiteral(expression: CypherExpr): Boolean = expression is CypherExpr.Literal &&
    (expression.value == null || expression.value is String || expression.value is Number || expression.value is Boolean)

private const val CLAUSES_WITH_SEPARATE_WHERE = 3
private val INTRINSIC_CALL_SITE_PROPERTIES = setOf(
    "caller_class", "caller_name", "caller_signature", "caller_descriptor",
    "callee_class", "callee_name", "callee_signature", "callee_descriptor", "ordinal", "line",
)
private val SELECTION_EXTRACTION_FAILURES = setOf(
    AnalysisStatus.INCOMPLETE, AnalysisStatus.RESOLUTION_FAILED, AnalysisStatus.NO_BODY,
)
private val SELECTION_STRUCTURE_GAPS = setOf(AnalysisStatus.ANALYZED_WITHOUT_CONTROL_FLOW, AnalysisStatus.NATIVE)
private val SELECTION_SCOPE_GAPS = setOf(AnalysisStatus.EXCLUDED_BY_SCOPE, AnalysisStatus.NOT_EXPANDED)
