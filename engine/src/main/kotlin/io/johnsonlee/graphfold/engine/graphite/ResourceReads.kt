package io.johnsonlee.graphfold.engine.graphite

import sootup.core.jimple.common.Local
import sootup.core.jimple.common.constant.StringConstant
import sootup.core.jimple.common.expr.AbstractInstanceInvokeExpr
import sootup.core.jimple.common.expr.AbstractInvokeExpr
import sootup.core.model.Body

/** Captures the explicit bundle/key subset; aliases, escapes and other loaders remain unknown reads. */
internal fun resourceReads(body: Body, applicationOwners: Set<String>): List<ResourceRead> {
    val assignments = body.stmts.filter { it.isJAssignStmt }.map { it.asJAssignStmt() }.groupBy { it.leftOp }
    val bundles = assignments.mapNotNull { (local, definitions) ->
        val call = definitions.singleOrNull()?.rightOp as? AbstractInvokeExpr
        val name = call?.takeIf(::simpleBundle)?.args?.singleOrNull() as? StringConstant
        if (local is Local && name != null) local to name.value.replace('.', '/') else null
    }.toMap()
    val independent = ResourceIndependentCalls(assignments.mapValues { it.value.singleOrNull()?.rightOp })
    val reads = mutableListOf<ResourceRead>()
    body.stmts.forEach { stmt ->
        val call = if (stmt.isInvokableStmt) stmt.asInvokableStmt().invokeExpr.orElse(null) else null
        val signature = call?.methodSignature
        val owner = signature?.declClassType?.fullyQualifiedName
        call?.let { reads += invocationReads(it, bundles, applicationOwners, independent) }
        if (call != null && simpleBundle(call)) {
            val target = if (stmt.isJAssignStmt) stmt.asJAssignStmt().leftOp else null
            if (target !in bundles) reads += ResourceRead(null, null)
        }
        // A returned, stored or passed bundle can be read through code outside this local model.
        val usedBundles = stmt.uses.filterIsInstance<Local>().filter { it in bundles }
        if (usedBundles.isNotEmpty() && owner != "java.util.ResourceBundle") {
            usedBundles.forEach { reads += ResourceRead(bundles[it], null) }
        }
    }
    return reads.distinct()
}

private fun simpleBundle(call: AbstractInvokeExpr): Boolean =
    call.methodSignature.declClassType.fullyQualifiedName == "java.util.ResourceBundle" &&
        call.methodSignature.name == "getBundle" && call.args.size == 1

private fun invocationReads(
    call: AbstractInvokeExpr, bundles: Map<Local, String>, applicationOwners: Set<String>, independent: ResourceIndependentCalls,
): List<ResourceRead> {
    val owner = call.methodSignature.declClassType.fullyQualifiedName
    val member = call.methodSignature.name
    return when {
        owner == "java.util.ResourceBundle" && member == "getBundle" -> {
            if (!simpleBundle(call) || call.args.singleOrNull() !is StringConstant) listOf(ResourceRead(null, null)) else emptyList()
        }
        owner == "java.util.ResourceBundle" -> {
            val bundle = bundles[(call as? AbstractInstanceInvokeExpr)?.base]
            val key = if (member in setOf("getString", "getObject", "getStringArray", "containsKey")) {
                (call.args.singleOrNull() as? StringConstant)?.value
            } else null
            listOf(ResourceRead(bundle, key))
        }
        owner in applicationOwners || independent.matches(call) -> emptyList()
        else -> listOf(ResourceRead(null, null))
    }
}
