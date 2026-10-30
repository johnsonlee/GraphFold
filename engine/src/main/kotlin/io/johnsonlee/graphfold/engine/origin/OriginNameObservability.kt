package io.johnsonlee.graphfold.engine.origin

import java.nio.file.Path
import org.objectweb.asm.Type
import org.objectweb.asm.Handle
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.InvokeDynamicInsnNode
import org.objectweb.asm.tree.MethodInsnNode

/** Narrow rendering only when bytecode flow proves its operands safe; stack capture has a separate guard. */
internal fun originNameObservability(
    nodes: List<ClassNode>, owners: Map<String, String>, dependencies: List<Path> = emptyList(),
): List<String> {
    val renamed = owners.filter { it.key != it.value }.keys.map { it.replace('.', '/') }.toSet()
    if (renamed.isEmpty()) return emptyList()
    val diagnostics = mutableListOf<String>()
    val hierarchy = OriginHierarchy(nodes, dependencies)
    val types = OriginRenderTypes(nodes, renamed)
    renamed.forEach { owner ->
        when (hierarchy.serializable(owner)) {
            OriginSubtype.YES -> diagnostics += "Runtime binary name is observable through Serializable: $owner"
            OriginSubtype.UNKNOWN -> diagnostics += "Unable to verify Serializable ancestry of renamed owner: $owner"
            OriginSubtype.NO -> Unit
        }
    }
    nodes.forEach { node ->
        node.methods.forEach { method ->
            val flow by lazy { OriginObjectFlow(node.name, method, types) }
            val observable = method.instructions.any { instruction ->
                observesInstruction(instruction, { flow }, hierarchy)
            }
            if (observable) diagnostics += "Runtime binary name may be observable through object rendering or stack capture " +
                "(including unverified constructor ancestry): " +
                "${node.name}.${method.name}${method.desc}"
        }
    }
    return diagnostics
}

private fun observesInstruction(instruction: AbstractInsnNode, flow: () -> OriginObjectFlow, hierarchy: OriginHierarchy): Boolean =
    when (instruction) {
        is MethodInsnNode -> observesExecution(instruction.owner, instruction.name, instruction.desc, hierarchy) ||
            (rendersObject(instruction.owner, instruction.name, instruction.desc) &&
                (!canNarrowRendering(instruction) || !safeCall(instruction, flow())))
        is InvokeDynamicInsnNode -> observesDynamicName(instruction, hierarchy) && !safeDynamic(instruction, flow(), hierarchy)
        else -> false
    }

private fun rendersObject(owner: String, name: String, descriptor: String): Boolean {
    if (name == "toString" && descriptor == "()Ljava/lang/String;") {
        return owner !in ORIGIN_SCALARS
    }
    val rendering = when (owner) {
        "java/lang/String" -> name in setOf("valueOf", "format", "formatted")
        "java/util/Objects" -> name == "toString"
        "java/lang/StringBuilder", "java/lang/StringBuffer" -> name in setOf("append", "insert", "<init>")
        "java/io/PrintStream", "java/io/PrintWriter" -> name in setOf("print", "println", "printf", "format")
        "java/util/Formatter" -> name == "format"
        "java/util/Arrays" -> name in setOf("toString", "deepToString")
        else -> kotlinRendering(owner, name)
    }
    return rendering && Type.getArgumentTypes(descriptor).any(::mayContainOrigin)
}

private fun kotlinRendering(owner: String, name: String): Boolean = owner.startsWith("kotlin/") &&
    (name.startsWith("joinTo") || name in KOTLIN_RENDERING_NAMES)

private fun mayContainOrigin(type: Type): Boolean = when (type.sort) {
    Type.OBJECT -> type.internalName !in ORIGIN_SCALARS
    Type.ARRAY -> mayContainOrigin(type.elementType)
    else -> false
}

private val KOTLIN_RENDERING_NAMES = setOf("stringPlus", "contentToString", "contentDeepToString",
    "format", "print", "println")

/** Explicit stack capture exposes executing JVM owners even when no application object is rendered. */
private fun observesStack(owner: String, name: String, descriptor: String): Boolean =
    owner == "java/lang/Throwable" || owner == "java/lang/StackTraceElement" || owner.startsWith("java/lang/StackWalker") ||
        (owner == "java/lang/Thread" && name in setOf("getStackTrace", "getAllStackTraces", "dumpStack")) ||
        (name == "getStackTrace" && descriptor == "()[Ljava/lang/StackTraceElement;")

private fun observesDynamicName(instruction: InvokeDynamicInsnNode, hierarchy: OriginHierarchy): Boolean =
    (instruction.bsm.owner == "java/lang/invoke/StringConcatFactory" &&
        (Type.getArgumentTypes(instruction.desc).any(::mayContainOrigin) || instruction.bsmArgs.any { !safeConcatConstant(it) })) ||
        instruction.bsmArgs.filterIsInstance<Handle>().any {
            observesCall(it.owner, it.name, it.desc, hierarchy)
        }

private fun observesCall(owner: String, name: String, descriptor: String, hierarchy: OriginHierarchy): Boolean =
    rendersObject(owner, name, descriptor) || observesExecution(owner, name, descriptor, hierarchy)

private fun observesExecution(owner: String, name: String, descriptor: String, hierarchy: OriginHierarchy): Boolean =
    observesStack(owner, name, descriptor) ||
        (name == "<init>" && hierarchy.subtype(owner, "java/lang/Throwable") != OriginSubtype.NO)

private fun operandCount(call: MethodInsnNode): Int =
    Type.getArgumentTypes(call.desc).size + if (call.opcode == Opcodes.INVOKESTATIC) 0 else 1

private fun safeCall(call: MethodInsnNode, flow: OriginObjectFlow): Boolean =
    (call.opcode == Opcodes.INVOKESTATIC || flow.nonNullReceiver(call, Type.getArgumentTypes(call.desc).size)) &&
        flow.safeOperands(call, operandCount(call))

private fun canNarrowRendering(call: MethodInsnNode): Boolean =
    (call.name == "toString" && call.opcode != Opcodes.INVOKESTATIC) ||
        (call.opcode == Opcodes.INVOKESTATIC && call.owner to call.name in STATIC_RENDERERS)

private fun safeDynamic(call: InvokeDynamicInsnNode, flow: OriginObjectFlow, hierarchy: OriginHierarchy): Boolean {
    val arguments = Type.getArgumentTypes(call.desc).size
    val handles = call.bsmArgs.filterIsInstance<Handle>()
    return when {
        call.bsm.owner == "java/lang/invoke/StringConcatFactory" ->
            call.bsmArgs.all(::safeConcatConstant) && flow.safeOperands(call, arguments)
        call.bsm.owner == "java/lang/invoke/LambdaMetafactory" && arguments == 1 && handles.size == 1 -> {
            val target = handles.single()
            target.tag in INSTANCE_HANDLES && target.name == "toString" && target.desc == "()Ljava/lang/String;" &&
                !observesExecution(target.owner, target.name, target.desc, hierarchy) &&
                flow.nonNullReceiver(call, 0) && flow.safeOperands(call, arguments)
        }
        else -> false
    }
}

private fun safeConcatConstant(value: Any): Boolean = value is String || value is Number
// Formatting/console/mutable-builder APIs need additional precondition, dispatch or heap models.
private val STATIC_RENDERERS = setOf("java/lang/String" to "valueOf", "java/util/Objects" to "toString",
    "java/util/Arrays" to "toString", "java/util/Arrays" to "deepToString", "kotlin/jvm/internal/Intrinsics" to "stringPlus")
private val INSTANCE_HANDLES = setOf(Opcodes.H_INVOKEVIRTUAL, Opcodes.H_INVOKEINTERFACE, Opcodes.H_INVOKESPECIAL)
