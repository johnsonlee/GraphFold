package io.johnsonlee.graphfold.engine.graphite

import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode

/** JVMS initialization of a class includes superclasses and default-method superinterfaces. */
internal class ClassInitialization {
    private val types = linkedMapOf<String, InitializationType>()

    fun add(node: ClassNode) {
        types[node.name.replace('/', '.')] = InitializationType(
            node.superName?.replace('/', '.'), node.interfaces.map { it.replace('/', '.') },
            node.access and Opcodes.ACC_INTERFACE != 0,
            node.methods.any {
                it.name != "<clinit>" && it.access and (Opcodes.ACC_ABSTRACT or Opcodes.ACC_STATIC or Opcodes.ACC_PRIVATE) == 0
            },
        )
    }

    fun dependencies(): Map<String, Set<String>> = types.mapValues { (_, type) ->
        val result = linkedSetOf<String>()
        if (!type.isInterface) {
            collectClass(type, result, mutableSetOf())
        }
        result
    }

    private fun collectClass(type: InitializationType, result: MutableSet<String>, visited: MutableSet<String>) {
        type.superName?.let { parent ->
            if (visited.add(parent)) {
                result.add(parent)
                types[parent]?.let { collectClass(it, result, visited) }
            }
        }
        type.interfaces.forEach { collectInterface(it, result, visited) }
    }

    private fun collectInterface(name: String, result: MutableSet<String>, visited: MutableSet<String>) {
        if (!visited.add(name)) return
        types[name]?.let { type ->
            type.interfaces.forEach { collectInterface(it, result, visited) }
            if (type.hasDefault) result.add(name)
        }
    }
}

private data class InitializationType(
    val superName: String?, val interfaces: List<String>, val isInterface: Boolean, val hasDefault: Boolean,
)
