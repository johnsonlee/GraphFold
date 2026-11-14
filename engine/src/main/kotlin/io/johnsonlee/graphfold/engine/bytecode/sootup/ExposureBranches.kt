package io.johnsonlee.graphfold.engine.bytecode.sootup


import io.johnsonlee.graphite.core.jvmMethodDescriptor
import sootup.core.model.Body
import java.util.IdentityHashMap
import sootup.core.jimple.common.stmt.Stmt

/** Invocation-local immutable CFG facts. Bodies are never retained past their observation callback. */
internal class ExposureBranches {
    private data class MethodFacts(
        val calls: Map<String, List<Int>>, val successors: List<List<Int>>,
        val branches: Map<ExposureBranchKey, List<Int>>,
    )
    private val methods = mutableMapOf<String, MethodFacts>()
    private val selected = mutableMapOf<String, MutableMap<Int, Set<ExposureBranchKey>>>()

    fun capture(identity: String, body: Body) {
        if (body.stmts.none { it.isJIfStmt }) return
        val statements = body.stmts
        val indexes = IdentityHashMap<Stmt, Int>().apply { statements.forEachIndexed { index, stmt -> put(stmt, index) } }
        val calls = statements.withIndex().mapNotNull { (index, stmt) ->
            stmt.takeIf { it.isInvokableStmt }?.asInvokableStmt()?.invokeExpr?.orElse(null)?.methodSignature?.let {
                val callee = "${it.declClassType.fullyQualifiedName}.${it.name}" +
                    jvmMethodDescriptor(it.parameterTypes.map { type -> type.toString() }, it.type.toString())
                callee to index
            }
        }.groupBy({ it.first }, { it.second })
        val branches = statements.withIndex().mapNotNull { (index, stmt) -> exposureBranchKey(stmt)?.let { it to index } }
            .groupBy({ it.first }, { it.second })
        methods[identity] = MethodFacts(calls,
            statements.map { body.controlFlowGraph.successors(it).map(indexes::getValue) }, branches)
    }

    fun select(ruleIndex: Int, caller: String, callee: String) {
        val facts = methods[caller] ?: return
        val call = facts.calls[callee]?.singleOrNull() ?: return
        selected.getOrPut(caller) { mutableMapOf() }[ruleIndex] = controllingBranches(facts, call)
    }

    /** Short-circuit OR can control another exposure predicate rather than the AB call directly. */
    private fun controllingBranches(facts: MethodFacts, call: Int): Set<ExposureBranchKey> {
        val remaining = facts.branches.filterValues { it.size == 1 }.mapValues { it.value.single() }.toMutableMap()
        val authorized = linkedSetOf<ExposureBranchKey>()
        val pending = ArrayDeque(listOf(call))
        while (pending.isNotEmpty()) {
            val target = pending.removeFirst()
            val predecessors = remaining.filterValues { branch ->
                val successors = facts.successors[branch].distinct()
                successors.size == 2 && successors.map { reaches(facts.successors, it, target, branch) }.distinct().size == 2
            }
            predecessors.forEach { (key, branch) ->
                authorized.add(key)
                remaining.remove(key)
                pending.addLast(branch)
            }
        }
        return authorized
    }

    fun authorized(identity: String, foldedRules: Set<Int>): Set<ExposureBranchKey> =
        selected[identity].orEmpty().filterKeys { it in foldedRules }.values.flatten().toSet()

}

private fun reaches(edges: List<List<Int>>, start: Int, target: Int, boundary: Int): Boolean {
    val pending = ArrayDeque(listOf(start))
    val visited = mutableSetOf(boundary)
    while (pending.isNotEmpty()) {
        val current = pending.removeFirst()
        if (current == target) return true
        if (visited.add(current)) pending.addAll(edges[current])
    }
    return false
}
