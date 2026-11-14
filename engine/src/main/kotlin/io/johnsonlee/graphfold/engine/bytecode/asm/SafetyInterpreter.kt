package io.johnsonlee.graphfold.engine.bytecode.asm

import io.johnsonlee.graphfold.engine.safety.ReturnNullness
import io.johnsonlee.graphfold.engine.safety.ProofStatus
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.IincInsnNode
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.IntInsnNode
import org.objectweb.asm.tree.JumpInsnNode
import org.objectweb.asm.tree.LabelNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.LookupSwitchInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.TableSwitchInsnNode
import org.objectweb.asm.tree.TypeInsnNode
import org.objectweb.asm.tree.VarInsnNode

/** Path-sensitive interpreter of original JVM instructions. Unsupported behavior never yields a semantic key. */
internal class SafetyInterpreter(
    private val analysis: StaticSafetyAnalysis, private val owner: ClassNode, private val method: MethodNode,
    private val active: Set<String>,
) {
    private val instructions = method.instructions.toArray()
    private val labels = instructions.withIndex().filter { it.value is LabelNode }.associate { it.value to it.index }
    private val identity = identity(owner.name, method.name, method.desc)
    private var remaining = MAX_STEPS
    private val visitedInstructions = mutableSetOf<Int>()

    fun evaluate(receiver: SafetyValue?, arguments: List<SafetyValue>): SafetyEvaluation {
        val locals = mutableMapOf<Int, SafetyValue>()
        var slot = 0
        if (receiver != null) locals[slot++] = receiver
        arguments.forEach { locals[slot] = it; slot += it.type.size }
        val result = run(SafetyState(locals), 0, emptySet())
        val residual = SafetyResidualBytecode(method).fingerprint(visitedInstructions)
        return if (residual == null) result else result.copy(facts = result.facts.retainResidual(identity, residual))
    }

    private fun run(state: SafetyState, start: Int, previous: Set<Int>): SafetyEvaluation {
        var index = start
        var result: SafetyEvaluation? = null
        val visited = previous.toMutableSet()
        while (index < instructions.size && result == null) {
            val instruction = instructions[index]
            visitedInstructions.add(index)
            if (--remaining < 0 || !visited.add(index)) {
                result = state.unknown("$identity: loop or analysis budget prevents termination proof")
            } else {
                val next = advance(state, instruction, index, visited)
                index = next.first
                result = next.second
            }
        }
        return result ?: state.unknown("$identity: no supported return")
    }

    private fun advance(
        state: SafetyState, instruction: AbstractInsnNode, index: Int, visited: Set<Int>,
    ): Pair<Int, SafetyEvaluation?> = when {
        instruction.opcode < 0 -> index + 1 to null
        instruction is JumpInsnNode -> if (instruction.opcode == Opcodes.GOTO) labels.getValue(instruction.label) to null
            else index to branch(state, instruction, index, visited)
        instruction is LookupSwitchInsnNode -> index to switch(state, instruction.keys, instruction.labels, instruction.dflt, visited)
        instruction is TableSwitchInsnNode -> index to switch(state,
            (instruction.min..instruction.max).toList(), instruction.labels, instruction.dflt, visited)
        else -> index + 1 to step(state, instruction, index)
    }

    private fun branch(state: SafetyState, instruction: JumpInsnNode, index: Int, visited: Set<Int>): SafetyEvaluation {
        if (instruction.opcode !in BRANCH_OPERATIONS) return state.unknown("$identity: unsupported branch ${instruction.opcode}")
        val unary = instruction.opcode in UNARY_INTEGER_BRANCHES ||
            instruction.opcode == Opcodes.IFNULL || instruction.opcode == Opcodes.IFNONNULL
        val right = if (unary) {
            if (instruction.opcode == Opcodes.IFNULL || instruction.opcode == Opcodes.IFNONNULL) SafetyValue.NULL
            else SafetyValue.integer(0)
        } else state.pop()
        val condition = SafetyCondition(instruction.opcode, state.pop(), right)
        val target = labels.getValue(instruction.label)
        val known = condition.known()
        return if (known != null) run(state, if (known) target else index + 1, visited)
        else condition.combine(condition.refine(state, true)?.let { run(it, target, visited) },
            condition.refine(state, false)?.let { run(it, index + 1, visited) })
    }

    private fun switch(
        state: SafetyState, keys: List<Int>, targets: List<LabelNode>, default: LabelNode, visited: Set<Int>,
    ): SafetyEvaluation {
        val selector = state.pop()
        val value = selector.integer
        if (value != null) return run(state, labels.getValue(targets.getOrNull(keys.indexOf(value)) ?: default), visited)
        var otherwise = state.fork()
        val cases = keys.mapIndexed { index, key ->
            val condition = SafetyCondition(Opcodes.IF_ICMPEQ, selector, SafetyValue.integer(key))
            val accepted = condition.refine(otherwise, true)?.let { run(it, labels.getValue(targets[index]), visited) }
            otherwise = otherwise.excludingInteger(selector, key)
            condition to accepted
        }
        return cases.asReversed().fold(run(otherwise, labels.getValue(default), visited)) { result, (condition, accepted) ->
            condition.combine(accepted, result)
        }
    }

    private fun step(state: SafetyState, instruction: AbstractInsnNode, index: Int): SafetyEvaluation? = when (instruction) {
        is VarInsnNode -> variable(state, instruction)
        is IincInsnNode -> {
            val value = state.locals[instruction.`var`]
            if (value == null) state.unknown("$identity: undefined local") else {
                state.locals[instruction.`var`] = SafetyIntegerOperation.ADD.evaluate(state.resolve(value),
                    SafetyValue.integer(instruction.incr))
                null
            }
        }
        is IntInsnNode -> if (instruction.opcode == Opcodes.BIPUSH || instruction.opcode == Opcodes.SIPUSH) {
            state.push(SafetyValue.integer(instruction.operand)); null
        } else state.unknown("$identity: unsupported allocation opcode ${instruction.opcode}")
        is LdcInsnNode -> constant(state, instruction.cst)
        is MethodInsnNode -> call(state, instruction, index)
        is FieldInsnNode -> field(state, instruction, index)
        is TypeInsnNode -> type(state, instruction, index)
        is InsnNode -> simple(state, instruction.opcode)
        else -> state.unknown("$identity: unsupported instruction ${instruction.opcode}")
    }

    private fun variable(state: SafetyState, instruction: VarInsnNode): SafetyEvaluation? = when (instruction.opcode) {
        Opcodes.ILOAD, Opcodes.LLOAD, Opcodes.FLOAD, Opcodes.DLOAD, Opcodes.ALOAD -> {
            val value = state.locals[instruction.`var`]
            if (value == null) state.unknown("$identity: undefined local") else { state.push(value); null }
        }
        Opcodes.ISTORE, Opcodes.LSTORE, Opcodes.FSTORE, Opcodes.DSTORE, Opcodes.ASTORE -> {
            state.locals[instruction.`var`] = state.pop(); null
        }
        else -> state.unknown("$identity: unsupported local instruction")
    }

    private fun constant(state: SafetyState, value: Any): SafetyEvaluation? {
        val constant = when (value) {
            is Int -> SafetyValue.integer(value)
            is String -> SafetyValue.string(value)
            is Long -> SafetyValue("long:$value", Type.LONG_TYPE)
            is Float -> SafetyValue("float:${value.toRawBits()}", Type.FLOAT_TYPE)
            is Double -> SafetyValue("double:${value.toRawBits()}", Type.DOUBLE_TYPE)
            else -> null
        }
        return if (constant == null) state.unknown("$identity: unsupported constant") else { state.push(constant); null }
    }

    private fun call(state: SafetyState, call: MethodInsnNode, index: Int): SafetyEvaluation? {
        val arguments = Type.getArgumentTypes(call.desc).map { state.pop() }.asReversed()
        val receiver = if (call.opcode == Opcodes.INVOKESTATIC) null else state.pop()
        if (receiver != null && !state.receiver(receiver, "$identity@$index")) return SafetyEvaluation(null, state.facts)
        val result = analysis.invoke(call, receiver, arguments, owner.name, active)
        state.facts = state.facts.merge(result.facts.conditional(state.feasible))
        if (result.facts.exceptions.status != ProofStatus.PROVEN) state.feasible = false
        return if (result.value == null) SafetyEvaluation(null, state.facts) else {
            if (Type.getReturnType(call.desc).sort != Type.VOID) state.push(result.value)
            null
        }
    }

    private fun field(state: SafetyState, field: FieldInsnNode, index: Int): SafetyEvaluation? {
        val declaration = analysis.field(field.owner, field.name, field.desc)
            ?: return state.unknown("$identity: missing field declaration ${field.owner}.${field.name}")
        val static = field.opcode == Opcodes.GETSTATIC || field.opcode == Opcodes.PUTSTATIC
        val write = field.opcode == Opcodes.PUTFIELD || field.opcode == Opcodes.PUTSTATIC
        if (static && field.owner != owner.name) initialize(state, field.owner)
        if (write) state.pop()
        val receiver = if (static) null else state.pop()
        return if (receiver != null && !state.receiver(receiver, "$identity@$index")) SafetyEvaluation(null, state.facts) else {
            if (declaration.access and Opcodes.ACC_VOLATILE != 0) state.effect("$identity@$index: volatile field access")
            if (write) {
                if (receiver?.fresh != true) state.effect("$identity@$index: write to externally observable state")
            } else state.push(SafetyValue.unknown(Type.getType(field.desc), analysis.readIdentity("$identity@$index")))
            null
        }
    }

    private fun initialize(state: SafetyState, initializedOwner: String) {
        val initialized = analysis.initialization(initializedOwner, active)
        state.facts = state.facts.merge(initialized.conditional(state.feasible))
        if (initialized.exceptions.status != ProofStatus.PROVEN) state.feasible = false
    }

    private fun type(state: SafetyState, instruction: TypeInsnNode, index: Int): SafetyEvaluation? = when (instruction.opcode) {
        Opcodes.NEW -> {
            initialize(state, instruction.desc)
            state.facts = state.facts.merge(SafetyFacts(
                effects = SafetyFacts.unproved("$identity@$index: allocation lifetime and escape closure are not established")))
            state.push(SafetyValue(analysis.readIdentity("new:$identity@$index"), Type.getObjectType(instruction.desc),
                ReturnNullness.NON_NULL, exactType = instruction.desc, stable = false, fresh = true)); null
        }
        Opcodes.CHECKCAST -> {
            val value = state.pop()
            val safe = value.nullness == ReturnNullness.NULL || value.exactType == instruction.desc ||
                instruction.desc == "java/lang/Object"
            if (!safe) state.exception("$identity@$index: cast target is not established")
            state.push(value.copy(type = Type.getObjectType(instruction.desc))); null
        }
        Opcodes.INSTANCEOF -> {
            val value = state.pop()
            val answer = when { value.nullness == ReturnNullness.NULL -> 0; value.exactType == instruction.desc -> 1; else -> null }
            val result = if (answer != null) SafetyValue.integer(answer)
                else SafetyValue("instanceof:${instruction.desc}(${value.expression})", Type.INT_TYPE, stable = value.stable)
            state.push(result); null
        }
        else -> state.unknown("$identity: unsupported type operation ${instruction.opcode}")
    }

    private fun simple(state: SafetyState, opcode: Int): SafetyEvaluation? = when (opcode) {
        Opcodes.IRETURN, Opcodes.LRETURN, Opcodes.FRETURN, Opcodes.DRETURN, Opcodes.ARETURN -> SafetyEvaluation(state.pop(), state.facts)
        Opcodes.RETURN -> SafetyEvaluation(SafetyValue.VOID, state.facts)
        Opcodes.ATHROW -> { state.pop(); state.exception("$identity: explicit throw", true); SafetyEvaluation(null, state.facts) }
        Opcodes.ARRAYLENGTH -> {
            val array = state.pop()
            if (!state.receiver(array, identity)) SafetyEvaluation(null, state.facts)
            else { state.push(SafetyValue("length(${array.expression})", Type.INT_TYPE, stable = array.stable)); null }
        }
        Opcodes.INEG -> {
            val value = state.pop()
            val result = if (value.integer != null) SafetyValue.integer(-value.integer)
                else SafetyValue("ineg(${value.expression})", Type.INT_TYPE, stable = value.stable)
            state.push(result); null
        }
        else -> {
            val operation = INTEGER_OPERATIONS[opcode]
            if (operation == null) stackOperation(state, opcode) else arithmetic(state, operation)
        }
    }

    private fun stackOperation(state: SafetyState, opcode: Int): SafetyEvaluation? = when (opcode) {
        Opcodes.NOP -> null
        Opcodes.ACONST_NULL -> { state.push(SafetyValue.NULL); null }
        Opcodes.ICONST_M1, Opcodes.ICONST_0, Opcodes.ICONST_1, Opcodes.ICONST_2,
        Opcodes.ICONST_3, Opcodes.ICONST_4, Opcodes.ICONST_5 -> { state.push(SafetyValue.integer(opcode - Opcodes.ICONST_0)); null }
        Opcodes.POP -> { state.pop(); null }
        Opcodes.DUP -> { state.push(state.stack.last()); null }
        Opcodes.SWAP -> { val first = state.pop(); val second = state.pop(); state.push(first); state.push(second); null }
        Opcodes.LCONST_0, Opcodes.LCONST_1 -> constant(state, (opcode - Opcodes.LCONST_0).toLong())
        Opcodes.FCONST_0, Opcodes.FCONST_1, Opcodes.FCONST_2 -> constant(state, (opcode - Opcodes.FCONST_0).toFloat())
        Opcodes.DCONST_0, Opcodes.DCONST_1 -> constant(state, (opcode - Opcodes.DCONST_0).toDouble())
        else -> state.unknown("$identity: unsupported opcode $opcode")
    }

    private fun arithmetic(state: SafetyState, operation: SafetyIntegerOperation): SafetyEvaluation? {
        val right = state.pop()
        val left = state.pop()
        if (operation == SafetyIntegerOperation.DIVIDE || operation == SafetyIntegerOperation.REMAINDER) {
            val knownZero = right.integer == 0
            val nonzero = if (right.integer != null) !knownZero
                else 0 in state.excludedIntegers[right.expression].orEmpty()
            if (!nonzero) state.exception("$identity: divisor may be zero", knownZero)
            if (knownZero) return SafetyEvaluation(null, state.facts)
        }
        state.push(operation.evaluate(left, right))
        return null
    }

    private companion object {
        const val MAX_STEPS = 10000
        val UNARY_INTEGER_BRANCHES = setOf(Opcodes.IFEQ, Opcodes.IFNE, Opcodes.IFLT, Opcodes.IFLE, Opcodes.IFGT, Opcodes.IFGE)
        val BRANCH_OPERATIONS = UNARY_INTEGER_BRANCHES + setOf(Opcodes.IF_ICMPEQ, Opcodes.IF_ICMPNE, Opcodes.IF_ICMPLT,
            Opcodes.IF_ICMPLE, Opcodes.IF_ICMPGT, Opcodes.IF_ICMPGE, Opcodes.IF_ACMPEQ, Opcodes.IF_ACMPNE,
            Opcodes.IFNULL, Opcodes.IFNONNULL)
    }
}
