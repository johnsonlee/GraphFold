package io.johnsonlee.graphfold.engine.bytecode.asm

import io.johnsonlee.graphfold.engine.safety.MethodSafetySummary
import io.johnsonlee.graphfold.engine.safety.ReturnNullness
import java.nio.file.Path
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode

/** Original, unfurled JVM semantics. No selected-control or runtime-reachability assumptions enter these summaries. */
internal class StaticSafetyAnalysis(artifact: Path, dependencies: List<Path>) : AutoCloseable {
    private val files = SafetyClassFiles(artifact, dependencies)
    private var readSequence = 0
    internal fun readIdentity(site: String): String = "$site:read${readSequence++}"

    fun summaries(): Map<String, MethodSafetySummary> = files.applications.values.flatMap { owner -> owner.methods.map { method ->
        val identity = identity(owner.name, method.name, method.desc)
        val arguments = Type.getArgumentTypes(method.desc).mapIndexed { index, type -> SafetyValue.unknown(type, "arg:$index", true) }
        val receiver = if (method.access and Opcodes.ACC_STATIC == 0) SafetyValue("this", Type.getObjectType(owner.name),
            ReturnNullness.NON_NULL, exactType = owner.name.takeIf { owner.access and Opcodes.ACC_FINAL != 0 }) else null
        val result = evaluate(owner, method, receiver, arguments, emptySet())
        identity to MethodSafetySummary(identity, result.facts.effects, result.facts.exceptions, result.facts.termination,
            result.semanticKey(), result.facts.dependencies, if (result.value == null) ReturnNullness.UNKNOWN else result.value.nullness)
    } }.toMap()

    internal fun evaluate(
        owner: ClassNode, method: MethodNode, receiver: SafetyValue?, arguments: List<SafetyValue>, active: Set<String>,
    ): SafetyEvaluation {
        val identity = identity(owner.name, method.name, method.desc)
        val unsupported = when {
            identity in active || active.size >= MAX_CALL_DEPTH -> "Recursive or excessively deep call graph: $identity"
            method.access and (Opcodes.ACC_NATIVE or Opcodes.ACC_ABSTRACT) != 0 -> "No inspectable method body: $identity"
            method.tryCatchBlocks.isNotEmpty() -> "Exceptional control-flow handlers are unsupported: $identity"
            method.access and Opcodes.ACC_SYNCHRONIZED != 0 -> "Synchronized method effects are unsupported: $identity"
            else -> null
        }
        val result = if (unsupported != null) SafetyEvaluation(null, SafetyFacts().unknown(unsupported))
        else SafetyInterpreter(this, owner, method, active + identity).evaluate(receiver, arguments)
        // Retain the inspected declaration, which may differ from the call site's symbolic owner.
        return result.copy(facts = result.facts.copy(dependencies = result.facts.dependencies + identity))
    }

    internal fun invoke(
        instruction: MethodInsnNode, receiver: SafetyValue?, arguments: List<SafetyValue>, caller: String, active: Set<String>,
    ): SafetyEvaluation {
        val called = identity(instruction.owner, instruction.name, instruction.desc)
        val contract = contract(instruction, receiver, arguments, called)
        if (contract != null) return contract
        val receiverType = receiver?.exactType
        val lookupOwner = lookupOwner(instruction, receiverType)
        val target = files.method(lookupOwner, instruction.name, instruction.desc)
        val exact = target != null && (instruction.opcode == Opcodes.INVOKESTATIC || instruction.opcode == Opcodes.INVOKESPECIAL ||
            receiverType != null || target.first.access and Opcodes.ACC_FINAL != 0 ||
            target.second.access and (Opcodes.ACC_FINAL or Opcodes.ACC_PRIVATE) != 0)
        val result = if (target == null || !exact) SafetyEvaluation(null, SafetyFacts().unknown("Unresolved call alternatives: $called"))
        else {
            val initialized = if (instruction.opcode == Opcodes.INVOKESTATIC && target.first.name != caller)
                initialization(target.first.name, active) else SafetyFacts()
            val evaluated = evaluate(target.first, target.second, receiver, arguments, active)
            evaluated.copy(facts = initialized.merge(evaluated.facts.conditional(initialized.normalCompletionProven)))
        }
        return result.copy(facts = result.facts.copy(dependencies = result.facts.dependencies + called))
    }

    private fun lookupOwner(instruction: MethodInsnNode, receiverType: String?): String = when (instruction.opcode) {
        Opcodes.INVOKEVIRTUAL, Opcodes.INVOKEINTERFACE ->
            if (receiverType == null) instruction.owner else receiverType
        else -> instruction.owner
    }

    private fun contract(
        instruction: MethodInsnNode, receiver: SafetyValue?, arguments: List<SafetyValue>, called: String,
    ): SafetyEvaluation? {
        val result = if (files.supportsContract(instruction.owner)) safetyContract(instruction, receiver, arguments) else null
        return result?.let { proof ->
            val value = proof.value?.let { if (it.boxed != null)
                it.copy(expression = readIdentity("boxed:$called"), stable = false) else it }
            proof.copy(value = value, facts = proof.facts.copy(dependencies = proof.facts.dependencies + called))
        }
    }

    internal fun initialization(owner: String, active: Set<String>): SafetyFacts {
        val type = files.find(owner)
        return when {
            owner == "java/lang/Object" -> SafetyFacts()
            "$owner.<initialization>" in active -> SafetyFacts().unknown("Cyclic initialization: $owner")
            type == null -> SafetyFacts().unknown("Missing class initialization inventory: $owner")
            else -> initialized(type, active)
        }
    }

    private fun initialized(type: ClassNode, active: Set<String>): SafetyFacts {
        val initializer = type.methods.singleOrNull { it.name == "<clinit>" }
        val own = if (initializer == null) SafetyFacts() else evaluate(type, initializer, null, emptyList(), active).facts
        val inherited = if (type.superName == null) SafetyFacts().unknown("Missing superclass inventory: ${type.name}")
            else initialization(type.superName, active + "${type.name}.<initialization>")
        val interfaces = if (type.interfaces.isEmpty()) SafetyFacts()
            else SafetyFacts().unknown("Interface initialization closure: ${type.name}")
        val prerequisites = inherited.merge(interfaces)
        return prerequisites.merge(own.conditional(prerequisites.normalCompletionProven))
    }

    internal fun field(owner: String, name: String, descriptor: String): FieldNode? {
        val declaration = files.find(owner) ?: return null
        return declaration.fields.singleOrNull { it.name == name && it.desc == descriptor }
    }

    override fun close() { files.close() }

    private companion object { const val MAX_CALL_DEPTH = 32 }
}

internal fun identity(owner: String, name: String, descriptor: String): String = "${owner.replace('/', '.')}.$name$descriptor"
