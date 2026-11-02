package io.johnsonlee.graphfold.engine.graphite

import io.johnsonlee.graphite.core.jvmMethodDescriptor
import sootup.core.jimple.common.Value
import sootup.core.jimple.common.constant.NumericConstant
import sootup.core.jimple.common.constant.StringConstant
import sootup.core.jimple.common.expr.AbstractInstanceInvokeExpr
import sootup.core.jimple.common.expr.AbstractInvokeExpr
import sootup.core.jimple.common.expr.JDynamicInvokeExpr
import sootup.core.jimple.common.ref.JStaticFieldRef
import sootup.core.signatures.MethodSignature

/** Explicit library contracts; unknown overloads and receivers remain potential resource readers. */
internal class ResourceIndependentCalls(private val definitions: Map<Value, Value?>) {
    fun matches(call: AbstractInvokeExpr): Boolean {
        val owner = call.methodSignature.declClassType.fullyQualifiedName
        val signature = signature(call.methodSignature)
        return when {
            call is JDynamicInvokeExpr -> scalarConcatenation(call)
            owner == "java.io.PrintStream" -> standardOutput(call, signature)
            owner == "java.lang.Class" -> signature in ClassValueQueries.signatures
            else -> signature in contracts[owner].orEmpty()
        }
    }

    private fun standardOutput(call: AbstractInvokeExpr, signature: String): Boolean {
        val base = (call as? AbstractInstanceInvokeExpr)?.base
        val field = origin(base, emptySet()) as? JStaticFieldRef
        val systemStream = field?.fieldSignature?.let {
            it.declClassType.fullyQualifiedName == "java.lang.System" && it.name in setOf("out", "err") &&
                it.type.toString() == "java.io.PrintStream"
        } == true
        val scalarObject = signature in objectOutputContracts && call.args.singleOrNull()?.type?.toString() in scalarObjects
        return (signature in outputContracts || scalarObject) && systemStream
    }

    private fun origin(value: Value?, visited: Set<Value>): Value? = when {
        value == null || value in visited -> null
        value in definitions -> origin(definitions[value], visited + value)
        else -> value
    }

    private fun scalarConcatenation(call: JDynamicInvokeExpr): Boolean {
        val bootstrap = call.bootstrapMethodSignature
        val validBootstrap = bootstrap.declClassType.fullyQualifiedName == "java.lang.invoke.StringConcatFactory" &&
            signature(bootstrap) in concatBootstraps && call.handleTag == STATIC_HANDLE
        val scalarArguments = call.methodSignature.parameterTypes.all { it.toString() in scalarTypes }
        val constantArguments = call.bootstrapArgs.all { it is StringConstant || it is NumericConstant<*> }
        return validBootstrap && scalarArguments && constantArguments && call.methodSignature.type.toString() == "java.lang.String"
    }

    private fun signature(method: MethodSignature): String = method.name +
        jvmMethodDescriptor(method.parameterTypes.map { it.toString() }, method.type.toString())

    companion object {
        private const val STATIC_HANDLE = 6
        private val primitiveDescriptors = listOf("Z", "B", "C", "S", "I", "J", "F", "D")
        private val scalarDescriptors = primitiveDescriptors + "Ljava/lang/String;"
        private val scalarTypes = setOf("boolean", "byte", "char", "short", "int", "long", "float", "double", "java.lang.String")
        private val scalarObjects = setOf("java.lang.String", "java.lang.Boolean", "java.lang.Byte", "java.lang.Character",
            "java.lang.Short", "java.lang.Integer", "java.lang.Long", "java.lang.Float", "java.lang.Double")
        private val objectOutputContracts = setOf("print(Ljava/lang/Object;)V", "println(Ljava/lang/Object;)V")
        private val outputContracts = scalarDescriptors.flatMap { listOf("print($it)V", "println($it)V") }.toSet() + "println()V"
        private const val LOOKUP_ARGUMENTS = "Ljava/lang/invoke/MethodHandles\$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;"
        private val concatBootstraps = setOf(
            "makeConcat($LOOKUP_ARGUMENTS)Ljava/lang/invoke/CallSite;",
            "makeConcatWithConstants(${LOOKUP_ARGUMENTS}Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;",
        )
        private val contracts = mapOf(
            "java.lang.Object" to setOf("<init>()V", "getClass()Ljava/lang/Class;"),
            "java.lang.String" to setOf("length()I", "isEmpty()Z", "equals(Ljava/lang/Object;)Z", "hashCode()I",
                "toString()Ljava/lang/String;", "charAt(I)C", "concat(Ljava/lang/String;)Ljava/lang/String;") +
                scalarDescriptors.map { "valueOf($it)Ljava/lang/String;" },
            "java.lang.StringBuilder" to builderContracts("StringBuilder"),
            "java.lang.StringBuffer" to builderContracts("StringBuffer"),
            "kotlin.jvm.internal.Intrinsics" to setOf("checkNotNullParameter(Ljava/lang/Object;Ljava/lang/String;)V",
                "checkNotNullExpressionValue(Ljava/lang/Object;Ljava/lang/String;)V", "checkNotNull(Ljava/lang/Object;)V",
                "checkNotNull(Ljava/lang/Object;Ljava/lang/String;)V"),
        ) + wrapperContracts()

        private fun builderContracts(name: String): Set<String> = setOf(
            "<init>()V", "<init>(I)V", "<init>(Ljava/lang/String;)V", "toString()Ljava/lang/String;", "length()I", "charAt(I)C",
        ) + scalarDescriptors.map { "append($it)Ljava/lang/$name;" }

        private fun wrapperContracts(): Map<String, Set<String>> = listOf(
            Triple("Boolean", "Z", "boolean"), Triple("Byte", "B", "byte"), Triple("Character", "C", "char"),
            Triple("Short", "S", "short"), Triple("Integer", "I", "int"), Triple("Long", "J", "long"),
            Triple("Float", "F", "float"), Triple("Double", "D", "double"),
        ).associate { (name, descriptor, primitive) ->
            "java.lang.$name" to setOf(
                "valueOf($descriptor)Ljava/lang/$name;", "valueOf(Ljava/lang/String;)Ljava/lang/$name;",
                "${primitive}Value()$descriptor", "toString($descriptor)Ljava/lang/String;", "toString()Ljava/lang/String;",
                "parse${name.replace("Integer", "Int")}(Ljava/lang/String;)$descriptor",
                "parse${name.replace("Integer", "Int")}(Ljava/lang/String;I)$descriptor",
            )
        }
    }
}
