package io.johnsonlee.graphfold.engine.bytecode.asm

import io.johnsonlee.graphfold.engine.safety.ReturnNullness
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.MethodInsnNode

/** Explicit JVM library contracts, never inferred from names or nullability annotations. */
internal fun safetyContract(call: MethodInsnNode, receiver: SafetyValue?, arguments: List<SafetyValue>): SafetyEvaluation? {
    val signature = "${call.owner}.${call.name}${call.desc}"
    return when {
        signature == "java/lang/Object.<init>()V" && call.opcode == Opcodes.INVOKESPECIAL ->
            SafetyEvaluation(SafetyValue.VOID, SafetyFacts())
        call.owner == "java/lang/String" && receiver != null -> stringContract(call, receiver, arguments)
        signature in UNBOXING && receiver != null -> SafetyEvaluation(receiver.boxed ?: SafetyValue(
            "unbox:$signature(${receiver.expression})", Type.getReturnType(call.desc), stable = receiver.stable), SafetyFacts())
        signature in BOXING && call.opcode == Opcodes.INVOKESTATIC -> SafetyEvaluation(SafetyValue(
            "box:$signature(${arguments.single().expression})", Type.getReturnType(call.desc), ReturnNullness.NON_NULL,
            exactType = call.owner, stable = arguments.single().stable, boxed = arguments.single()), SafetyFacts())
        signature in KOTLIN_NULL_CHECKS && call.opcode == Opcodes.INVOKESTATIC -> nullCheck(arguments.first(), signature)
        else -> null
    }
}

private fun stringContract(call: MethodInsnNode, receiver: SafetyValue, arguments: List<SafetyValue>): SafetyEvaluation? {
    val value = when (call.name + call.desc) {
        "length()I" -> if (receiver.string != null) SafetyValue.integer(receiver.string.length)
            else SafetyValue("strlen(${receiver.expression})", Type.INT_TYPE, stable = receiver.stable)
        "equals(Ljava/lang/Object;)Z" -> stringEquality(receiver, arguments.single())
        else -> null
    }
    return value?.let { SafetyEvaluation(it, SafetyFacts()) }
}

private fun stringEquality(receiver: SafetyValue, argument: SafetyValue): SafetyValue {
    val constant = when {
        argument.nullness == ReturnNullness.NULL -> false
        receiver.string != null && argument.string != null -> receiver.string == argument.string
        argument.exactType != null && argument.exactType != "java/lang/String" -> false
        else -> null
    }
    return if (constant != null) SafetyValue.integer(if (constant) 1 else 0)
        else SafetyValue("stringEquals(${receiver.expression},${argument.expression})", Type.INT_TYPE,
            stable = receiver.stable && argument.stable)
}

private fun nullCheck(value: SafetyValue, identity: String): SafetyEvaluation {
    val proof = when (value.nullness) {
        ReturnNullness.NON_NULL -> SafetyFacts.proved("$identity receives a statically non-null argument")
        ReturnNullness.NULL -> SafetyFacts.refuted("$identity receives null")
        else -> SafetyFacts.unproved("$identity argument nullness is not established")
    }
    return SafetyEvaluation(if (value.nullness == ReturnNullness.NULL) null else SafetyValue.VOID, SafetyFacts(exceptions = proof))
}

private val UNBOXING = setOf(
    "java/lang/Integer.intValue()I", "java/lang/Boolean.booleanValue()Z", "java/lang/Byte.byteValue()B",
    "java/lang/Short.shortValue()S", "java/lang/Character.charValue()C", "java/lang/Long.longValue()J",
    "java/lang/Float.floatValue()F", "java/lang/Double.doubleValue()D",
)
private val BOXING = setOf(
    "java/lang/Integer.valueOf(I)Ljava/lang/Integer;", "java/lang/Boolean.valueOf(Z)Ljava/lang/Boolean;",
    "java/lang/Byte.valueOf(B)Ljava/lang/Byte;", "java/lang/Short.valueOf(S)Ljava/lang/Short;",
    "java/lang/Character.valueOf(C)Ljava/lang/Character;", "java/lang/Long.valueOf(J)Ljava/lang/Long;",
    "java/lang/Float.valueOf(F)Ljava/lang/Float;", "java/lang/Double.valueOf(D)Ljava/lang/Double;",
)
private val KOTLIN_NULL_CHECKS = setOf(
    "kotlin/jvm/internal/Intrinsics.checkNotNullParameter(Ljava/lang/Object;Ljava/lang/String;)V",
    "kotlin/jvm/internal/Intrinsics.checkNotNullExpressionValue(Ljava/lang/Object;Ljava/lang/String;)V",
    "kotlin/jvm/internal/Intrinsics.checkNotNull(Ljava/lang/Object;)V",
    "kotlin/jvm/internal/Intrinsics.checkNotNull(Ljava/lang/Object;Ljava/lang/String;)V",
)
