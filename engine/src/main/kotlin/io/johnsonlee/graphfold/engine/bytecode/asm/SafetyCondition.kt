package io.johnsonlee.graphfold.engine.bytecode.asm

import io.johnsonlee.graphfold.engine.safety.ReturnNullness
import org.objectweb.asm.Opcodes

internal data class SafetyCondition(val opcode: Int, val left: SafetyValue, val right: SafetyValue) {
    private val equality get() = opcode in setOf(Opcodes.IFEQ, Opcodes.IFNE, Opcodes.IF_ICMPEQ, Opcodes.IF_ICMPNE,
        Opcodes.IF_ACMPEQ, Opcodes.IF_ACMPNE, Opcodes.IFNULL, Opcodes.IFNONNULL)
    private val negated get() = opcode in setOf(Opcodes.IFNE, Opcodes.IF_ICMPNE, Opcodes.IF_ACMPNE, Opcodes.IFNONNULL)
    private val reference get() = opcode in setOf(Opcodes.IF_ACMPEQ, Opcodes.IF_ACMPNE, Opcodes.IFNULL, Opcodes.IFNONNULL)
    val stable get() = left.stable && right.stable
    val key: String get() = if (equality) "eq(${listOf(left.expression, right.expression).sorted().joinToString(",")})"
        else "cmp:$opcode(${left.expression},${right.expression})"

    fun known(): Boolean? = if (equality) equalKnown()?.let { if (negated) !it else it }
        else left.integer?.let { a -> right.integer?.let { b -> compare(a, b) } }

    private fun equalKnown(): Boolean? = when {
        left.expression == right.expression -> true
        reference && left.nullness == ReturnNullness.NULL -> nullableEquality(right.nullness)
        reference && right.nullness == ReturnNullness.NULL -> nullableEquality(left.nullness)
        left.integer != null && right.integer != null -> left.integer == right.integer
        else -> null
    }

    fun refine(original: SafetyState, taken: Boolean): SafetyState? {
        val state = original.fork()
        val equal = taken != negated
        return when {
            reference -> refineReference(state, equal)
            equality -> refineInteger(state, equal)
            else -> state.also { it.feasible = false }
        }
    }

    private fun refineReference(state: SafetyState, equal: Boolean): SafetyState {
        val subject = when {
            left.nullness == ReturnNullness.NULL -> right
            right.nullness == ReturnNullness.NULL -> left
            else -> null
        }
        if (subject != null) {
            state.nullFacts[subject.expression] = if (equal) ReturnNullness.NULL else ReturnNullness.NON_NULL
            if (!subject.expression.startsWith("arg:")) state.feasible = false
        } else state.feasible = false
        return state
    }

    private fun refineInteger(state: SafetyState, equal: Boolean): SafetyState? {
        val subject = if (left.integer == null && right.integer != null) left else right
        val constant = left.integer ?: right.integer
        if (constant == null) state.feasible = false
        else if (equal) {
            if (constant in state.excludedIntegers[subject.expression].orEmpty()) return null
            state.integerFacts[subject.expression] = constant
        } else state.excludedIntegers[subject.expression] = state.excludedIntegers[subject.expression].orEmpty() + constant
        if (!subject.expression.startsWith("arg:")) state.feasible = false
        return state
    }

    fun combine(taken: SafetyEvaluation?, otherwise: SafetyEvaluation?): SafetyEvaluation = when {
        taken == null -> requireNotNull(otherwise)
        otherwise == null -> taken
        else -> SafetyEvaluation(combineValues(taken.value, otherwise.value), taken.facts.merge(otherwise.facts))
    }

    private fun combineValues(first: SafetyValue?, second: SafetyValue?): SafetyValue? = when {
        first == null || second == null -> null
        first.expression == second.expression && first.type == second.type -> joinedValue(first, second)
        else -> {
            val positive = if (negated) second else first
            val negative = if (negated) first else second
            joinedValue(first, second).copy(expression = "if($key,${positive.expression},${negative.expression})",
                integer = null, string = null, fresh = false, boxed = null, stable = stable && first.stable && second.stable)
        }
    }

    private fun compare(a: Int, b: Int): Boolean? = when (opcode) {
        Opcodes.IFLT, Opcodes.IF_ICMPLT -> a < b
        Opcodes.IFLE, Opcodes.IF_ICMPLE -> a <= b
        Opcodes.IFGT, Opcodes.IF_ICMPGT -> a > b
        Opcodes.IFGE, Opcodes.IF_ICMPGE -> a >= b
        else -> null
    }

    private fun nullableEquality(value: ReturnNullness): Boolean? = when (value) {
        ReturnNullness.NULL -> true
        ReturnNullness.NON_NULL -> false
        else -> null
    }
}

private fun joinedValue(first: SafetyValue, second: SafetyValue): SafetyValue = first.copy(
    stable = first.stable && second.stable,
    nullness = if (first.nullness == second.nullness) first.nullness else ReturnNullness.UNKNOWN,
    integer = first.integer.takeIf { it == second.integer }, string = first.string.takeIf { it == second.string },
    exactType = first.exactType.takeIf { it == second.exactType }, boxed = first.boxed.takeIf { it == second.boxed },
)
