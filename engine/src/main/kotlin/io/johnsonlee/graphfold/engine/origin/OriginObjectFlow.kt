package io.johnsonlee.graphfold.engine.origin

import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.TypeInsnNode
import org.objectweb.asm.tree.analysis.Analyzer
import org.objectweb.asm.tree.analysis.AnalyzerException
import org.objectweb.asm.tree.analysis.BasicInterpreter
import org.objectweb.asm.tree.analysis.BasicValue
import org.objectweb.asm.tree.analysis.Frame

/** A must-proof of safe rendering. Unknown references never become safe merely through a cast. */
internal class OriginObjectFlow(owner: String, method: MethodNode, types: OriginRenderTypes) {
    private val instructions = method.instructions
    private val frames: Array<Frame<BasicValue>?>? = try {
        Analyzer(RenderInterpreter(types)).analyze(owner, method)
    } catch (_: AnalyzerException) {
        null // Invalid or unsupported bytecode keeps the original conservative observation guard.
    }

    fun safeOperands(instruction: AbstractInsnNode, count: Int): Boolean {
        val frame = frames?.get(instructions.indexOf(instruction)) ?: return false
        return frame.stackSize >= count && (frame.stackSize - count until frame.stackSize).all { index ->
            (frame.getStack(index) as RenderValue).safe
        }
    }

    fun nonNullReceiver(instruction: AbstractInsnNode, arguments: Int): Boolean {
        val frame = frames?.get(instructions.indexOf(instruction)) ?: return false
        val index = frame.stackSize - arguments - 1
        return index >= 0 && (frame.getStack(index) as RenderValue).nonNull
    }
}

/** Exact allocation types only. Declared parameter/field types are not concrete receiver identities. */
internal class OriginRenderTypes(nodes: List<ClassNode>, private val renamed: Set<String>) {
    private val classes = nodes.associateBy { it.name }

    fun safeAllocation(name: String): Boolean = defaultRendering(name, mutableSetOf())

    private fun defaultRendering(name: String, visiting: MutableSet<String>): Boolean {
        val node = classes[name]
        return when {
            name in renamed -> false
            name == "java/lang/Object" || name in ORIGIN_SCALARS -> true
            node == null || !visiting.add(name) -> false
            node.interfaces.isNotEmpty() || node.methods.any(::customRendering) -> false
            else -> node.superName?.let { defaultRendering(it, visiting) } == true
        }
    }

    // Object.toString invokes hashCode; formatting can additionally dispatch through Formattable.
    private fun customRendering(method: MethodNode): Boolean =
        method.name to method.desc in setOf("toString" to "()Ljava/lang/String;", "hashCode" to "()I")
}

private class RenderValue(type: Type?, val safe: Boolean, val nonNull: Boolean) : BasicValue(type) {
    override fun equals(other: Any?): Boolean =
        other is RenderValue && super.equals(other) && safe == other.safe && nonNull == other.nonNull
    override fun hashCode(): Int = (super.hashCode() * HASH_FACTOR + safe.hashCode()) * HASH_FACTOR + nonNull.hashCode()

    companion object { private const val HASH_FACTOR = 31 }
}

/** Every nonnull value producer returns RenderValue; copy and nary operations retain that invariant. */
private class RenderInterpreter(private val types: OriginRenderTypes) : BasicInterpreter(Opcodes.ASM9) {
    override fun newValue(type: Type?): BasicValue? = super.newValue(type)?.let { value ->
        RenderValue(value.type, safeDeclaredType(type), type != null && !value.isReference)
    }

    override fun newOperation(instruction: AbstractInsnNode): BasicValue = when (instruction.opcode) {
        Opcodes.ACONST_NULL -> RenderValue(BasicValue.REFERENCE_VALUE.type, true, false)
        Opcodes.NEW -> RenderValue(Type.getObjectType((instruction as TypeInsnNode).desc), types.safeAllocation(instruction.desc), true)
        else -> render(super.newOperation(instruction)).let { value ->
            if (instruction is LdcInsnNode && instruction.cst is String) RenderValue(value.type, true, true) else value
        }
    }

    override fun unaryOperation(instruction: AbstractInsnNode, value: BasicValue): BasicValue? =
        if (instruction.opcode == Opcodes.CHECKCAST) value else super.unaryOperation(instruction, value)?.let(::render)

    override fun binaryOperation(instruction: AbstractInsnNode, first: BasicValue, second: BasicValue): BasicValue? =
        super.binaryOperation(instruction, first, second)?.let(::render)

    private fun render(value: BasicValue): BasicValue =
        if (value is RenderValue) value else RenderValue(value.type, safeDeclaredType(value.type), !value.isReference)

    override fun merge(first: BasicValue, second: BasicValue): BasicValue {
        if (first == second) return first
        val type = if (first.isReference && second.isReference) BasicValue.REFERENCE_VALUE.type else super.merge(first, second).type
        val left = first as RenderValue
        val right = second as RenderValue
        return RenderValue(type, left.safe && right.safe, left.nonNull && right.nonNull)
    }
}

private fun safeDeclaredType(type: Type?): Boolean = when (type?.sort) {
    Type.OBJECT -> type.internalName in ORIGIN_SCALARS
    Type.ARRAY -> type.elementType.sort != Type.OBJECT
    null -> false
    else -> true
}

internal val ORIGIN_SCALARS = setOf("java/lang/String", "java/lang/Boolean", "java/lang/Byte", "java/lang/Short",
    "java/lang/Integer", "java/lang/Long", "java/lang/Float", "java/lang/Double", "java/lang/Character")
