package io.johnsonlee.graphfold.engine.bytecode.asm

import java.security.MessageDigest
import org.objectweb.asm.ConstantDynamic
import org.objectweb.asm.Handle
import org.objectweb.asm.Type
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.IincInsnNode
import org.objectweb.asm.tree.IntInsnNode
import org.objectweb.asm.tree.InvokeDynamicInsnNode
import org.objectweb.asm.tree.JumpInsnNode
import org.objectweb.asm.tree.LabelNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.LookupSwitchInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.MultiANewArrayInsnNode
import org.objectweb.asm.tree.TableSwitchInsnNode
import org.objectweb.asm.tree.TypeInsnNode
import org.objectweb.asm.tree.VarInsnNode

/** Unselected production instructions never disappear because a path proof did not traverse them. */
internal class SafetyResidualBytecode(private val method: MethodNode) {
    private val all = method.instructions.toArray()
    private val code = all.filter { it.opcode >= 0 }
    private val ordinals = code.withIndex().associate { it.value to it.index }
    private val leaders = (listOf(0) + code.flatMap { instruction ->
        val next = ordinals.getValue(instruction) + 1
        when (instruction) {
            is JumpInsnNode -> listOf(target(instruction.label), next)
            is LookupSwitchInsnNode -> (instruction.labels + instruction.dflt).map(::target) + next
            is TableSwitchInsnNode -> (instruction.labels + instruction.dflt).map(::target) + next
            else -> if (instruction.opcode in RETURN_OR_THROW) listOf(next) else emptyList()
        }
    }).distinct().sorted()

    fun fingerprint(visited: Set<Int>): String? {
        val residual = all.withIndex().filter { it.value.opcode >= 0 && it.index !in visited }.map { (_, instruction) ->
            encode(listOf(block(ordinals.getValue(instruction)), instruction.opcode, operands(instruction)))
        }
        return residual.takeIf { it.isNotEmpty() }?.let {
            val bytes = encode(it).toByteArray(Charsets.UTF_8)
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { byte -> "%02x".format(byte) }
            "${it.size}:$hash"
        }
    }

    private fun operands(instruction: AbstractInsnNode): Any? = when (instruction) {
        is IntInsnNode -> instruction.operand
        is VarInsnNode -> instruction.`var`
        is TypeInsnNode -> instruction.desc
        is FieldInsnNode -> listOf(instruction.owner, instruction.name, instruction.desc)
        is MethodInsnNode -> listOf(instruction.owner, instruction.name, instruction.desc, instruction.itf)
        is InvokeDynamicInsnNode -> listOf(instruction.name, instruction.desc, instruction.bsm, instruction.bsmArgs.toList())
        is JumpInsnNode -> block(target(instruction.label))
        is LdcInsnNode -> instruction.cst
        is IincInsnNode -> listOf(instruction.`var`, instruction.incr)
        is TableSwitchInsnNode -> listOf(instruction.min, instruction.max, block(target(instruction.dflt)),
            instruction.labels.map { block(target(it)) })
        is LookupSwitchInsnNode -> listOf(instruction.keys, block(target(instruction.dflt)), instruction.labels.map { block(target(it)) })
        is MultiANewArrayInsnNode -> listOf(instruction.desc, instruction.dims)
        else -> null
    }

    private fun target(label: LabelNode): Int {
        var next: AbstractInsnNode? = label
        while (next != null && next.opcode < 0) next = next.next
        return next?.let(ordinals::getValue) ?: code.size
    }

    private fun block(ordinal: Int): Int = leaders.indexOfLast { it <= ordinal }

    private fun encode(value: Any?): String {
        val body = when (value) {
            null -> "null"
            is List<*> -> value.joinToString("") { encode(it) }
            is Type -> "type:${value.descriptor}"
            is Handle -> encode(listOf(value.tag, value.owner, value.name, value.desc, value.isInterface))
            is ConstantDynamic -> encode(listOf(value.name, value.descriptor, value.bootstrapMethod,
                (0 until value.bootstrapMethodArgumentCount).map(value::getBootstrapMethodArgument)))
            is Float -> "float:${value.toRawBits()}"
            is Double -> "double:${value.toRawBits()}"
            else -> "${value.javaClass.name}:$value"
        }
        return "${body.length}:$body"
    }

    private companion object {
        val RETURN_OR_THROW = setOf(org.objectweb.asm.Opcodes.IRETURN, org.objectweb.asm.Opcodes.LRETURN,
            org.objectweb.asm.Opcodes.FRETURN, org.objectweb.asm.Opcodes.DRETURN, org.objectweb.asm.Opcodes.ARETURN,
            org.objectweb.asm.Opcodes.RETURN, org.objectweb.asm.Opcodes.ATHROW)
    }
}
