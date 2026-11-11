package io.johnsonlee.graphfold.engine.graphite

import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode

/** JVMS initialization includes superclasses and superinterfaces declaring non-abstract, non-static methods. */
internal class ClassInitialization {
    private val types = linkedMapOf<String, InitializationType>()

    fun add(node: ClassNode) {
        types[node.name.replace('/', '.')] = InitializationType(
            node.superName?.replace('/', '.'), node.interfaces.map { it.replace('/', '.') },
            node.access and Opcodes.ACC_INTERFACE != 0,
            node.methods.filter {
                it.name != "<clinit>" && it.access and (Opcodes.ACC_ABSTRACT or Opcodes.ACC_STATIC) == 0
            }.mapTo(linkedSetOf()) { "${node.name.replace('/', '.')}.${it.name}${it.desc}" },
        )
    }

    /** Interface declarations requiring interface initialization when an implementing class initializes. */
    fun initializationTriggerMethods(): Set<String> =
        types.values.filter { it.isInterface }.flatMap { it.initializationTriggerMethods }.toSet()

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
            if (type.initializationTriggerMethods.isNotEmpty()) result.add(name)
        }
    }
}

private data class InitializationType(
    val superName: String?, val interfaces: List<String>, val isInterface: Boolean, val initializationTriggerMethods: Set<String>,
)
