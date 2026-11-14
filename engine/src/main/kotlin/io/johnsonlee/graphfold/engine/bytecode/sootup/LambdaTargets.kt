package io.johnsonlee.graphfold.engine.bytecode.sootup

import io.johnsonlee.graphite.core.jvmMethodDescriptor
import sootup.core.jimple.common.constant.MethodHandle
import sootup.core.jimple.common.constant.MethodType
import sootup.core.jimple.common.expr.JDynamicInvokeExpr
import sootup.core.model.Body
import sootup.core.signatures.MethodSignature

/** A factory associates its implementation with the enclosing body, but does not prove invocation. */
internal fun survivingLambdaTargets(body: Body): Set<String> = body.stmts.filter { it.isInvokableStmt }.mapNotNull {
    (it.asInvokableStmt().invokeExpr.orElse(null) as? JDynamicInvokeExpr)?.let(::lambdaImplementation)
}.toSet()

private fun lambdaImplementation(call: JDynamicInvokeExpr): String? {
    val bootstrap = call.bootstrapMethodSignature
    val descriptor = jvmMethodDescriptor(bootstrap.parameterTypes.map { it.toString() }, bootstrap.type.toString())
    val valid = call.handleTag == STATIC_HANDLE && bootstrap.declClassType.fullyQualifiedName ==
        "java.lang.invoke.LambdaMetafactory" && bootstrap.name == "metafactory" && descriptor == METAFACTORY_DESCRIPTOR &&
        call.bootstrapArgs.size == METAFACTORY_ARGUMENTS && call.bootstrapArgs[0] is MethodType && call.bootstrapArgs[2] is MethodType
    val handle = call.bootstrapArgs.getOrNull(1) as? MethodHandle ?: return null
    val signature = handle.referenceSignature as? MethodSignature
    return if (valid && signature != null && handle.kind in IMPLEMENTATION_KINDS) {
        "${signature.declClassType.fullyQualifiedName}.${signature.name}" +
            jvmMethodDescriptor(signature.parameterTypes.map { it.toString() }, signature.type.toString())
    } else null
}

private const val METAFACTORY_ARGUMENTS = 3
private const val STATIC_HANDLE = 6
private const val METAFACTORY_DESCRIPTOR = "(Ljava/lang/invoke/MethodHandles\$Lookup;Ljava/lang/String;" +
    "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;" +
    "Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;"
private val IMPLEMENTATION_KINDS = setOf(MethodHandle.Kind.REF_INVOKE_STATIC, MethodHandle.Kind.REF_INVOKE_SPECIAL,
    MethodHandle.Kind.REF_INVOKE_VIRTUAL)
