package io.johnsonlee.graphfold.engine.bytecode.asm

import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type

internal enum class SafetyIntegerOperation(val opcode: Int, val symbol: String) {
    ADD(Opcodes.IADD, "iadd"), SUBTRACT(Opcodes.ISUB, "isub"), MULTIPLY(Opcodes.IMUL, "imul"),
    DIVIDE(Opcodes.IDIV, "idiv"), REMAINDER(Opcodes.IREM, "irem"), AND(Opcodes.IAND, "iand"),
    OR(Opcodes.IOR, "ior"), XOR(Opcodes.IXOR, "ixor"), SHIFT_LEFT(Opcodes.ISHL, "ishl"),
    SHIFT_RIGHT(Opcodes.ISHR, "ishr"), UNSIGNED_SHIFT_RIGHT(Opcodes.IUSHR, "iushr");

    fun evaluate(left: SafetyValue, right: SafetyValue): SafetyValue {
        val a = left.integer
        val b = right.integer
        val result = if (a != null && b != null) integerConstant(this, a, b) else null
        if (result != null) return SafetyValue.integer(result)
        val identity = integerIdentity(opcode, left, right)
        return if (identity != null) identity else
            SafetyValue("$symbol(${left.expression},${right.expression})", Type.INT_TYPE, stable = left.stable && right.stable)
    }
}

private fun integerIdentity(opcode: Int, left: SafetyValue, right: SafetyValue): SafetyValue? = when (opcode) {
    Opcodes.IADD -> when { left.integer == 0 -> right; right.integer == 0 -> left; else -> null }
    Opcodes.ISUB -> left.takeIf { right.integer == 0 }
    Opcodes.IMUL -> when { left.integer == 1 -> right; right.integer == 1 -> left; else -> null }
    else -> null
}

private fun integerConstant(operation: SafetyIntegerOperation, left: Int, right: Int): Int? = when (operation) {
    SafetyIntegerOperation.ADD -> left + right
    SafetyIntegerOperation.SUBTRACT -> left - right
    SafetyIntegerOperation.MULTIPLY -> left * right
    SafetyIntegerOperation.DIVIDE -> divide(left, right, false)
    SafetyIntegerOperation.REMAINDER -> divide(left, right, true)
    SafetyIntegerOperation.AND -> left and right
    SafetyIntegerOperation.OR -> left or right
    SafetyIntegerOperation.XOR -> left xor right
    SafetyIntegerOperation.SHIFT_LEFT -> left shl right
    SafetyIntegerOperation.SHIFT_RIGHT -> left shr right
    SafetyIntegerOperation.UNSIGNED_SHIFT_RIGHT -> left ushr right
}

internal val INTEGER_OPERATIONS = SafetyIntegerOperation.entries.associateBy { it.opcode }

private fun divide(left: Int, right: Int, remainder: Boolean): Int? =
    if (right == 0) null else if (remainder) left % right else left / right
