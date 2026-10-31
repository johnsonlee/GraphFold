package io.johnsonlee.graphfold.engine.graphite

import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.analysis.Analyzer
import org.objectweb.asm.tree.analysis.AnalyzerException
import org.objectweb.asm.tree.analysis.BasicInterpreter
import org.objectweb.asm.tree.analysis.BasicValue

/**
 * Opaque third-party calls may inspect a whole object graph, including application objects hidden in collections.
 * Only proven scalar values cross this boundary without a model; all other reference provenance fails closed.
 */
internal class ExternalObjectEscape(private val classes: Map<String, ClassNode>) {
    fun diagnostics(): List<String> = classes.values.flatMap { owner ->
        owner.methods.flatMap { method ->
            val calls = method.instructions.toArray().withIndex().filter { (_, instruction) ->
                instruction is MethodInsnNode && external(instruction.owner)
            }
            if (calls.isEmpty()) emptyList() else try {
                val interpreter = ApplicationTypes(classes.keys)
                val frames = Analyzer(interpreter).analyze(owner.name, method)
                calls.mapNotNull { (index, instruction) ->
                    val call = instruction as MethodInsnNode
                    val frame = frames[index]
                    val count = Type.getArgumentTypes(call.desc).size + if (call.opcode == Opcodes.INVOKESTATIC) 0 else 1
                    val escapes = frame != null && (frame.stackSize - count until frame.stackSize)
                        .any { interpreter.observable(frame.getStack(it)) }
                    if (escapes) "Unmodeled external object graph escape: ${owner.name}.${method.name}${method.desc} -> " +
                        "${call.owner}.${call.name}${call.desc}" else null
                }
            } catch (_: AnalyzerException) {
                listOf("Unable to verify external object escape: ${owner.name}.${method.name}${method.desc}")
            }
        }
    }

    private fun external(owner: String): Boolean {
        if (owner in classes || owner.startsWith("kotlin/")) return false
        return owner in SERIALIZERS || ClassLoader.getPlatformClassLoader().getResource("$owner.class") == null
    }

    private class ApplicationTypes(private val classes: Set<String>) : BasicInterpreter(Opcodes.ASM9) {
        override fun newValue(type: Type?): BasicValue? =
            if (type != null && type.sort in setOf(Type.OBJECT, Type.ARRAY)) BasicValue(type) else super.newValue(type)

        override fun newOperation(instruction: AbstractInsnNode): BasicValue? {
            val constant = (instruction as? LdcInsnNode)?.cst
            return if (constant is Type && constant.sort == Type.OBJECT && constant.internalName in classes) {
                APPLICATION_VALUE
            } else super.newOperation(instruction)
        }

        override fun unaryOperation(instruction: AbstractInsnNode, value: BasicValue): BasicValue? =
            if (instruction.opcode == Opcodes.CHECKCAST && application(value)) value else super.unaryOperation(instruction, value)

        override fun merge(first: BasicValue, second: BasicValue): BasicValue = when {
            first == second -> first
            application(first) || application(second) -> APPLICATION_VALUE
            first.isReference && second.isReference ->
                if (!observable(first) && !observable(second)) SCALAR_REFERENCE else BasicValue.REFERENCE_VALUE
            else -> super.merge(first, second)
        }

        fun observable(value: BasicValue): Boolean {
            val type = value.type ?: return false
            return type.sort == Type.ARRAY || type.sort == Type.OBJECT && type.internalName !in VALUE_ONLY_TYPES
        }

        fun application(value: BasicValue): Boolean {
            val type = value.type ?: return false
            val element = if (type.sort == Type.ARRAY) type.elementType else type
            return value == APPLICATION_VALUE || element.sort == Type.OBJECT && element.internalName in classes
        }
    }

    private companion object {
        // Collections and Optional are deliberately absent: their reachable object graph may include application state.
        val VALUE_ONLY_TYPES = setOf("null", "java/lang/String", "java/lang/Boolean", "java/lang/Byte",
            "java/lang/Character", "java/lang/Short", "java/lang/Integer", "java/lang/Long", "java/lang/Float", "java/lang/Double")
        // This interpreter tracks escape provenance, not assignability. String represents the
        // scalar-reference category after joining proven strings, boxed primitives and null.
        // CHECKCAST still uses its real target type and cannot turn this summary into a proof of that type.
        val SCALAR_REFERENCE = BasicValue(Type.getObjectType("java/lang/String"))
        val APPLICATION_VALUE = BasicValue(Type.getObjectType("graphfold/ApplicationObject"))
        val SERIALIZERS = setOf("java/beans/XMLEncoder", "java/beans/XMLDecoder",
            "java/io/ObjectInputStream", "java/io/ObjectOutputStream")
    }
}
