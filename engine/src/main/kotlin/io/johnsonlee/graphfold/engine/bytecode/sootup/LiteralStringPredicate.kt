package io.johnsonlee.graphfold.engine.bytecode.sootup

import sootup.core.jimple.common.Local
import sootup.core.jimple.common.Value
import sootup.core.jimple.common.constant.StringConstant
import sootup.core.jimple.common.expr.JVirtualInvokeExpr
import sootup.core.model.Body

/** String is final; equals on a known literal receiver is total and does not invoke methods on its argument. */
internal fun literalStringPredicate(value: Value, body: Body): Boolean {
    val call = value as? JVirtualInvokeExpr ?: return false
    val signature = call.methodSignature
    val contract = signature.declClassType.fullyQualifiedName == "java.lang.String" && signature.name == "equals" &&
        signature.parameterTypes.map { it.toString() } == listOf("java.lang.Object") && signature.type.toString() == "boolean"
    return contract && call.args.size == 1 && literalOrigin(call.base, body, emptySet())
}

private fun literalOrigin(value: Value, body: Body, seen: Set<Value>): Boolean {
    if (value in seen) return false
    return when (value) {
        is StringConstant -> true
        is Local -> {
            // Count identity definitions too: a parameter with a conditional reassignment is not a literal origin.
            val definition = body.stmts.filter { it.def.orElse(null) == value }.singleOrNull()
            definition?.isJAssignStmt == true && literalOrigin(definition.asJAssignStmt().rightOp, body, seen + value)
        }
        else -> false
    }
}
