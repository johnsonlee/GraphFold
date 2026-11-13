package io.johnsonlee.graphfold.engine.bytecode.asm

import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.JumpInsnNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.MethodNode

/** Bounded totality/effect proof, not the dependency observation or no-retention proof. */
internal fun disposablePrimitiveMethods(owner: ClassNode): Set<String> = owner.methods.filter { method ->
    method.access and REQUIRED == REQUIRED && method.access and FORBIDDEN == 0 &&
        Type.getArgumentTypes(method.desc).all(::integerType) && integerType(Type.getReturnType(method.desc)) &&
        method.tryCatchBlocks.isEmpty() && disposableInstructions(method)
}.map { "${owner.name.replace('/', '.')}.${it.name}${it.desc}" }.toSet()

private fun integerType(type: Type): Boolean = type.sort in Type.BOOLEAN..Type.INT

private fun disposableInstructions(method: MethodNode): Boolean {
    val instructions = method.instructions.toArray()
    return instructions.any { it.opcode == Opcodes.IRETURN } &&
        instructions.withIndex().all { (index, instruction) ->
            when {
                instruction.opcode < 0 -> true
                instruction is JumpInsnNode -> instruction.opcode in JUMPS &&
                    method.instructions.indexOf(instruction.label) > index
                instruction is LdcInsnNode -> instruction.cst is Int
                else -> instruction.opcode in OPERATIONS
            }
        }
}

private const val REQUIRED = Opcodes.ACC_PRIVATE or Opcodes.ACC_STATIC
private const val FORBIDDEN = Opcodes.ACC_SYNCHRONIZED or Opcodes.ACC_NATIVE or Opcodes.ACC_ABSTRACT
private val JUMPS = setOf(Opcodes.GOTO, Opcodes.IFEQ, Opcodes.IFNE, Opcodes.IFLT, Opcodes.IFGE,
    Opcodes.IFGT, Opcodes.IFLE, Opcodes.IF_ICMPEQ, Opcodes.IF_ICMPNE, Opcodes.IF_ICMPLT,
    Opcodes.IF_ICMPGE, Opcodes.IF_ICMPGT, Opcodes.IF_ICMPLE)
private val OPERATIONS = setOf(Opcodes.NOP, Opcodes.ICONST_M1, Opcodes.ICONST_0, Opcodes.ICONST_1,
    Opcodes.ICONST_2, Opcodes.ICONST_3, Opcodes.ICONST_4, Opcodes.ICONST_5, Opcodes.BIPUSH, Opcodes.SIPUSH,
    Opcodes.ILOAD, Opcodes.ISTORE, Opcodes.IRETURN, Opcodes.IADD, Opcodes.ISUB, Opcodes.IMUL, Opcodes.INEG,
    Opcodes.IAND, Opcodes.IOR, Opcodes.IXOR, Opcodes.ISHL, Opcodes.ISHR, Opcodes.IUSHR, Opcodes.IINC,
    Opcodes.I2B, Opcodes.I2C, Opcodes.I2S, Opcodes.POP, Opcodes.DUP)
