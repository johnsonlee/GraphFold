package io.johnsonlee.graphfold.engine.origin

import java.nio.file.Path
import org.objectweb.asm.Type
import org.objectweb.asm.Handle
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.InvokeDynamicInsnNode
import org.objectweb.asm.tree.MethodInsnNode

/** Until receiver provenance is proved, object rendering is conservatively checked across the compilation. */
internal fun originNameObservability(
    nodes: List<ClassNode>, owners: Map<String, String>, dependencies: List<Path> = emptyList(),
): List<String> {
    val renamed = owners.filter { it.key != it.value }.keys.map { it.replace('.', '/') }.toSet()
    if (renamed.isEmpty()) return emptyList()
    val diagnostics = mutableListOf<String>()
    val hierarchy = OriginHierarchy(nodes, dependencies)
    renamed.forEach { owner ->
        when (hierarchy.serializable(owner)) {
            OriginSubtype.YES -> diagnostics += "Runtime binary name is observable through Serializable: $owner"
            OriginSubtype.UNKNOWN -> diagnostics += "Unable to verify Serializable ancestry of renamed owner: $owner"
            OriginSubtype.NO -> Unit
        }
    }
    nodes.forEach { node ->
        node.methods.forEach { method ->
            val observable = method.instructions.any { instruction ->
                when (instruction) {
                    is MethodInsnNode -> observesCall(instruction.owner, instruction.name, instruction.desc, hierarchy)
                    is InvokeDynamicInsnNode -> observesDynamicName(instruction, hierarchy)
                    else -> false
                }
            }
            if (observable) diagnostics += "Runtime binary name may be observable through object rendering or stack capture " +
                "(including unverified constructor ancestry): " +
                "${node.name}.${method.name}${method.desc}"
        }
    }
    return diagnostics
}

private fun rendersObject(owner: String, name: String, descriptor: String): Boolean {
    if (name == "toString" && descriptor == "()Ljava/lang/String;") {
        return owner !in SCALAR_NAMES
    }
    val rendering = when (owner) {
        "java/lang/String" -> name in setOf("valueOf", "format", "formatted")
        "java/util/Objects" -> name == "toString"
        "java/lang/StringBuilder", "java/lang/StringBuffer" -> name == "append"
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
    Type.OBJECT -> type.internalName !in SCALAR_NAMES
    Type.ARRAY -> mayContainOrigin(type.elementType)
    else -> false
}

private val SCALAR_NAMES = setOf("java/lang/String", "java/lang/Boolean", "java/lang/Byte", "java/lang/Short",
    "java/lang/Integer", "java/lang/Long", "java/lang/Float", "java/lang/Double", "java/lang/Character")

private val KOTLIN_RENDERING_NAMES = setOf("stringPlus", "contentToString", "contentDeepToString",
    "format", "print", "println")

/** Explicit stack capture exposes executing JVM owners even when no application object is rendered. */
private fun observesStack(owner: String, name: String, descriptor: String): Boolean =
    owner == "java/lang/Throwable" || owner == "java/lang/StackTraceElement" || owner.startsWith("java/lang/StackWalker") ||
        (owner == "java/lang/Thread" && name in setOf("getStackTrace", "getAllStackTraces", "dumpStack")) ||
        (name == "getStackTrace" && descriptor == "()[Ljava/lang/StackTraceElement;")

private fun observesDynamicName(instruction: InvokeDynamicInsnNode, hierarchy: OriginHierarchy): Boolean =
    (instruction.bsm.owner == "java/lang/invoke/StringConcatFactory" &&
        Type.getArgumentTypes(instruction.desc).any(::mayContainOrigin)) ||
        instruction.bsmArgs.filterIsInstance<Handle>().any {
            observesCall(it.owner, it.name, it.desc, hierarchy)
        }

private fun observesCall(owner: String, name: String, descriptor: String, hierarchy: OriginHierarchy): Boolean =
    rendersObject(owner, name, descriptor) || observesStack(owner, name, descriptor) ||
        (name == "<init>" && hierarchy.subtype(owner, "java/lang/Throwable") != OriginSubtype.NO)
