package io.johnsonlee.graphfold.engine.bytecode.sootup


import io.johnsonlee.graphite.core.jvmMethodDescriptor
import sootup.core.jimple.common.expr.JDynamicInvokeExpr
import sootup.core.jimple.common.expr.JNewExpr
import sootup.core.jimple.common.ref.JFieldRef
import sootup.core.model.Body

/** Calls whose effects need a body; the exact resource API and value-only contracts stay local. */
internal fun dependencyResourceCalls(body: Body, trustedIntrinsics: Boolean): Set<String> {
    val definitions = body.stmts.filter { it.isJAssignStmt }.map { it.asJAssignStmt() }.groupBy { it.leftOp }
    val independent = ResourceIndependentCalls(definitions.mapValues { it.value.singleOrNull()?.rightOp })
    return body.stmts.mapNotNull { stmt ->
        val call = if (stmt.isInvokableStmt) stmt.asInvokableStmt().invokeExpr.orElse(null) else null
        call?.takeUnless {
            val owner = it.methodSignature.declClassType.fullyQualifiedName
            owner == "java.util.ResourceBundle" ||
                (independent.matches(it) && (owner != "kotlin.jvm.internal.Intrinsics" || trustedIntrinsics))
        }?.methodSignature?.let { signature ->
            "${signature.declClassType.fullyQualifiedName}.${signature.name}" +
                jvmMethodDescriptor(signature.parameterTypes.map { it.toString() }, signature.type.toString())
        }
    }.toSet()
}

/**
 * Initialization candidates from folded operations; invokedynamic pseudoowners are not initialized classes.
 * Instance-field references are also included conservatively; field identities must resolve before selecting an owner.
 */
internal fun dependencyResourceInitializers(body: Body): Set<String> = body.stmts.flatMap { stmt ->
    val call = if (stmt.isInvokableStmt) stmt.asInvokableStmt().invokeExpr.orElse(null) else null
    val callOwner = call?.takeUnless { it is JDynamicInvokeExpr }?.let { it.methodSignature.declClassType.fullyQualifiedName }
    val assigned = if (stmt.isJAssignStmt) stmt.asJAssignStmt().leftOp else null
    val fieldOwners = (stmt.uses + listOfNotNull(assigned)).filterIsInstance<JFieldRef>()
        .map { field ->
            val signature = field.fieldSignature
            "${signature.declClassType.fullyQualifiedName}.${signature.name}:" +
                jvmMethodDescriptor(emptyList(), signature.type.toString()).removePrefix("()")
        }
    val allocated = if (stmt.isJAssignStmt) stmt.asJAssignStmt().rightOp as? JNewExpr else null
    fieldOwners + listOfNotNull(callOwner, allocated?.let { it.type.toString() })
}.toSet()
