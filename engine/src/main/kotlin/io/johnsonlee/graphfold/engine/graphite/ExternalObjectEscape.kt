package io.johnsonlee.graphfold.engine.graphite

import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.TypeInsnNode
import org.objectweb.asm.tree.InvokeDynamicInsnNode
import org.objectweb.asm.tree.MultiANewArrayInsnNode
import org.objectweb.asm.Handle
import org.objectweb.asm.ConstantDynamic
import org.objectweb.asm.tree.analysis.Frame
import org.objectweb.asm.tree.analysis.Analyzer
import org.objectweb.asm.tree.analysis.AnalyzerException
import org.objectweb.asm.tree.analysis.BasicInterpreter
import org.objectweb.asm.tree.analysis.BasicValue

/**
 * Opaque third-party calls may inspect a whole object graph, including application objects hidden in collections.
 * Only proven scalars and modeled immutable value graphs cross this boundary; other reference provenance fails closed.
 */
internal class ExternalObjectEscape(private val classes: Map<String, ClassNode>) {
    fun diagnostics(): List<String> = methodDiagnostics().values.flatten().map { it.message }

    fun methodDiagnostics(): Map<String, List<MethodAnalysisGap>> = buildMap {
        classes.values.forEach { owner -> owner.methods.forEach { method ->
            val diagnostics = inspect(owner, method)
            if (diagnostics.isNotEmpty()) put("${owner.name.replace('/', '.')}.${method.name}${method.desc}", diagnostics)
        } }
    }

    private fun inspect(owner: ClassNode, method: MethodNode): List<MethodAnalysisGap> {
        val calls = method.instructions.toArray().withIndex().filter { (_, instruction) ->
            instruction is MethodInsnNode && external(instruction.owner)
        }
        if (calls.isEmpty()) return emptyList()
        return try {
            val interpreter = ApplicationTypes(classes.keys)
            val frames = Analyzer(interpreter).analyze(owner.name, method)
            calls.mapNotNull { (index, instruction) ->
                val call = instruction as MethodInsnNode
                val frame = frames[index]
                val values = frame?.let { escapedValues(call, it, interpreter) }.orEmpty()
                if (values.isNotEmpty()) {
                    val owners = observedOwners(values)
                    MethodAnalysisGap(
                        "Unmodeled external object graph escape: ${owner.name}.${method.name}${method.desc} -> " +
                            "${call.owner}.${call.name}${call.desc}",
                        "${call.owner.replace('/', '.')}.${call.name}${call.desc}",
                        globalObservation = owners == null,
                        observedOwners = owners.orEmpty(),
                    )
                } else null
            }
        } catch (_: AnalyzerException) {
            listOf(MethodAnalysisGap(
                "Unable to verify external object escape: ${owner.name}.${method.name}${method.desc}", globalObservation = true,
            ))
        }
    }

    private fun escapedValues(
        call: MethodInsnNode, frame: Frame<BasicValue>, interpreter: ApplicationTypes,
    ): List<BasicValue> {
        val count = Type.getArgumentTypes(call.desc).size + if (call.opcode == Opcodes.INVOKESTATIC) 0 else 1
        val values = (frame.stackSize - count until frame.stackSize).map(frame::getStack)
        val logging = call.owner == "org/slf4j/Logger" && call.opcode == Opcodes.INVOKEINTERFACE &&
            call.name in LOGGING_METHODS && call.desc.startsWith("(Ljava/lang/String;") && call.desc.endsWith(")V") &&
            !interpreter.application(values.first()) && values.drop(1).all(interpreter::scalar)
        return if (logging) emptyList() else values.filter(interpreter::observable)
    }

    // Bound only final DTOs whose complete field and behavior graph remains within modeled
    // values and other final DTOs. Erased objects, arrays, collections and opaque callbacks
    // retain a global observation gap; a guessed runtime type must never narrow the proof.
    private fun observedOwners(values: List<BasicValue>): Set<String>? {
        val pending = linkedSetOf<String>()
        var bounded = values.all { boundedType(it.type, pending) }
        val observed = linkedSetOf<String>()
        while (bounded && pending.isNotEmpty()) {
            val name = pending.first().also(pending::remove)
            if (!observed.add(name)) continue
            bounded = classes[name]?.let { boundedOwner(it, pending) } == true
        }
        return if (bounded) observed.mapTo(linkedSetOf()) { it.replace('/', '.') } else null
    }

    private fun boundedOwner(owner: ClassNode, pending: MutableSet<String>): Boolean {
        val shape = owner.access and Opcodes.ACC_FINAL != 0 && owner.superName == "java/lang/Object" && owner.interfaces.isEmpty()
        return shape && BoundedPayloadMetadata.owner(owner) && owner.fields.all {
            BoundedPayloadMetadata.field(it) && boundedType(Type.getType(it.desc), pending)
        } &&
            owner.methods.all { boundedMethod(it, pending) }
    }

    private fun boundedType(type: Type?, pending: MutableSet<String>): Boolean = when (type?.sort) {
        null, Type.ARRAY -> false
        Type.OBJECT -> if (type.internalName in VALUE_ONLY_TYPES) true else {
            pending.add(type.internalName)
            type.internalName in classes
        }
        else -> true
    }

    private fun boundedMethod(method: MethodNode, pending: MutableSet<String>): Boolean {
        if (method.access and (Opcodes.ACC_NATIVE or Opcodes.ACC_ABSTRACT) != 0) return false
        val types = Type.getArgumentTypes(method.desc).toList() + Type.getReturnType(method.desc)
        return BoundedPayloadMetadata.method(method) && types.all { boundedType(it, pending) } &&
            method.instructions.all { boundedInstruction(it, pending) }
    }

    private fun boundedInstruction(instruction: AbstractInsnNode, pending: MutableSet<String>): Boolean = when (instruction) {
        is FieldInsnNode -> boundedType(Type.getObjectType(instruction.owner), pending)
        is TypeInsnNode -> boundedType(Type.getObjectType(instruction.desc), pending)
        is MethodInsnNode -> if (instruction.owner == "java/lang/Object" && instruction.name == "<init>") true else {
            val types = Type.getArgumentTypes(instruction.desc).toList() + Type.getReturnType(instruction.desc)
            boundedType(Type.getObjectType(instruction.owner), pending) && types.all { boundedType(it, pending) }
        }
        is LdcInsnNode -> instruction.cst !is Type && instruction.cst !is Handle && instruction.cst !is ConstantDynamic
        is InvokeDynamicInsnNode -> false
        is MultiANewArrayInsnNode -> false
        else -> true
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

        override fun naryOperation(instruction: AbstractInsnNode, values: MutableList<out BasicValue>): BasicValue? {
            val call = instruction as? MethodInsnNode
            val factory = call?.takeIf { it.opcode == Opcodes.INVOKESTATIC }
                ?.let { it.owner + "." + it.name + it.desc in VALUE_FACTORIES } == true
            return if (factory && values.none(::observable)) {
                VALUE_GRAPH_REFERENCE
            } else super.naryOperation(instruction, values)
        }

        override fun merge(first: BasicValue, second: BasicValue): BasicValue = when {
            first == second -> first
            application(first) || application(second) -> APPLICATION_VALUE
            first.isReference && second.isReference ->
                if (!observable(first) && !observable(second)) {
                    if (first == VALUE_GRAPH_REFERENCE || second == VALUE_GRAPH_REFERENCE) VALUE_GRAPH_REFERENCE else SCALAR_REFERENCE
                } else BasicValue.REFERENCE_VALUE
            else -> super.merge(first, second)
        }

        fun observable(value: BasicValue): Boolean {
            if (value == VALUE_GRAPH_REFERENCE) return false
            return !scalar(value)
        }

        fun scalar(value: BasicValue): Boolean {
            val type = value.type
            return when {
                value === VALUE_GRAPH_REFERENCE -> false
                type == null -> true
                else -> type.sort != Type.ARRAY && (type.sort != Type.OBJECT || type.internalName in VALUE_ONLY_TYPES)
            }
        }

        fun application(value: BasicValue): Boolean {
            val type = value.type ?: return false
            val element = if (type.sort == Type.ARRAY) type.elementType else type
            return value == APPLICATION_VALUE || element.sort == Type.OBJECT && element.internalName in classes
        }
    }

    private companion object {
        // A declared collection type alone proves nothing about its contents. Only exact immutable
        // factories with proven scalar/immutable arguments produce the separate value-graph summary.
        val LOGGING_METHODS = setOf("trace", "debug", "info", "warn", "error")
        val VALUE_FACTORIES = buildSet {
            for (collection in listOf("List", "Set")) for (count in 0..10) {
                add("java/util/$collection.of(" + "Ljava/lang/Object;".repeat(count) + ")Ljava/util/$collection;")
            }
            for (count in 0..10) {
                add("java/util/Map.of(" + "Ljava/lang/Object;".repeat(count * 2) + ")Ljava/util/Map;")
            }
            listOf("List", "Set", "Map").forEach { collection ->
                add("java/util/Collections.empty$collection()Ljava/util/$collection;")
            }
            add("java/util/Collections.singleton(Ljava/lang/Object;)Ljava/util/Set;")
            add("java/util/Collections.singletonList(Ljava/lang/Object;)Ljava/util/List;")
            add("java/util/Collections.singletonMap(Ljava/lang/Object;Ljava/lang/Object;)Ljava/util/Map;")
            add("java/util/Optional.empty()Ljava/util/Optional;")
            listOf("of", "ofNullable").forEach { name ->
                add("java/util/Optional.$name(Ljava/lang/Object;)Ljava/util/Optional;")
            }
        }
        val VALUE_GRAPH_REFERENCE: BasicValue = ImmutableGraphValue
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
